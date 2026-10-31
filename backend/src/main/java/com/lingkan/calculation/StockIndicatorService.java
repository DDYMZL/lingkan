package com.lingkan.calculation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.calculation.dto.BoardRowDto;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.datasource.provider.impl.EastMoneyWeeklyKlineProvider;
import com.lingkan.dividend.entity.StockDividend;
import com.lingkan.dividend.entity.StockShareEvent;
import com.lingkan.dividend.mapper.StockDividendMapper;
import com.lingkan.dividend.mapper.StockShareEventMapper;
import com.lingkan.financial.entity.StockFinancial;
import com.lingkan.financial.mapper.StockFinancialMapper;
import com.lingkan.metrics.service.QuoteSnapshotService;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.entity.StockWeeklyQuote;
import com.lingkan.stock.mapper.StockWeeklyQuoteMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页看板指标聚合服务（spec 010 §3.5）。
 *
 * <p>单只股票的看板行 = 行情快照 + 股息率 TTM（计算）+ PE TTM（计算）
 * + 周线五指标（计算，v1.2）+ 六档目标价（计算）。
 * 本服务只做编排与取数，计算逻辑全部在 {@code calculation} 包的纯计算器中；
 * 计算器不反向依赖本服务（亦不依赖 metrics 模块），保持可独立单元测试。
 *
 * <p>取数范围：分红/股本事件/财报/周线均取自同步后的本地表（stock_dividends /
 * stock_share_events / stock_financials / stock_weekly_quotes），
 * 看板与推送路径<b>不直连数据源</b>（FR-10.14）。
 *
 * <p>周线取数避免 N+1：批量路径一次 {@code in (stock_id)} 查询后在内存分组，
 * 单只路径按 {@code stock_id} 单查；两者都只读最近 {@code bars-limit} 根前复权周线，
 * 不跨复权基准混用早于窗口的旧行。
 */
@Service
public class StockIndicatorService {

    /** 数据状态：行情正常 */
    public static final String DATA_NORMAL = "NORMAL";
    /** 数据状态：行情缺失/过期（保留最后有效数据语义由行情调度落实，此处仅标记无行情） */
    public static final String DATA_STALE = "STALE";
    /** 数据状态：停牌（行情在返回但无最新价） */
    public static final String DATA_SUSPENDED = "SUSPENDED";

    /** 财务查询条数上限：TTM 4 季 + 上一年 Q3 换算用，8 条足够 */
    private static final int FINANCIAL_FETCH_LIMIT = 8;

    /** 周线查询的采集来源：只读同一来源，避免不同来源的复权基准混用 */
    private static final String WEEKLY_SOURCE = EastMoneyWeeklyKlineProvider.CODE;

    /**
     * 周线日期下界的余量（周）。查询窗口 = bars-limit + 余量，
     * 覆盖节假日短周与同步滞后，实际参与计算的根数仍由 {@code bars-limit} 截断。
     */
    private static final int WEEKLY_WINDOW_BUFFER_WEEKS = 8;

    private final QuoteSnapshotService quoteService;
    private final StockDividendMapper dividendMapper;
    private final StockShareEventMapper shareEventMapper;
    private final StockFinancialMapper financialMapper;
    private final StockWeeklyQuoteMapper weeklyQuoteMapper;
    private final BoardProperties boardProperties;

    public StockIndicatorService(QuoteSnapshotService quoteService,
                                 StockDividendMapper dividendMapper,
                                 StockShareEventMapper shareEventMapper,
                                 StockFinancialMapper financialMapper,
                                 StockWeeklyQuoteMapper weeklyQuoteMapper,
                                 BoardProperties boardProperties) {
        this.quoteService = quoteService;
        this.dividendMapper = dividendMapper;
        this.shareEventMapper = shareEventMapper;
        this.financialMapper = financialMapper;
        this.weeklyQuoteMapper = weeklyQuoteMapper;
        this.boardProperties = boardProperties;
    }

    /** 批量聚合（一次批量行情请求 + 一次批量周线查询，避免逐只打上游或 N+1） */
    public List<BoardRowDto> resolveAll(List<Stock> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return List.of();
        }
        Map<String, QuoteResult> quotes = new LinkedHashMap<>();
        quoteService.getQuotes(stocks.stream().map(Stock::getStockCode).toList())
                .forEach(q -> quotes.putIfAbsent(q.stockCode(), q));
        Map<Long, List<StockWeeklyQuote>> weeklyBars = loadWeeklyBarsBatch(
                stocks.stream().map(Stock::getId).toList());
        List<BoardRowDto> rows = new ArrayList<>(stocks.size());
        for (Stock stock : stocks) {
            rows.add(resolveInternal(stock, quotes.get(stock.getStockCode()),
                    weeklyBars.getOrDefault(stock.getId(), List.of()), true));
        }
        return rows;
    }

    /** 单只完整聚合；quote 可预先从批量请求传入。 */
    public BoardRowDto resolve(Stock stock, QuoteResult quote) {
        return resolveInternal(stock, quote, loadWeeklyBars(stock.getId()), true);
    }

    /**
     * 行情推送聚合：重算股息率、PE 与周线五指标，不重复计算不随行情变化的目标价。
     *
     * <p>周线指标必须随推送重算：本周未收盘周线的收盘价用实时价覆盖（Q7-8），
     * 因此五个价位参考线随行情变化（spec 010 §5.2）。
     */
    public BoardRowDto resolveQuoteUpdate(Stock stock, QuoteResult quote) {
        return resolveInternal(stock, quote, loadWeeklyBars(stock.getId()), false);
    }

    private BoardRowDto resolveInternal(Stock stock, QuoteResult quote,
                                        List<StockWeeklyQuote> weeklyBars,
                                        boolean includeTargetPrices) {
        List<StockDividend> dividends = dividendMapper.selectList(
                Wrappers.<StockDividend>lambdaQuery()
                        .eq(StockDividend::getStockId, stock.getId()));
        List<StockShareEvent> events = shareEventMapper.selectList(
                Wrappers.<StockShareEvent>lambdaQuery()
                        .eq(StockShareEvent::getStockId, stock.getId()));
        List<StockFinancial> financials = financialMapper.selectList(
                Wrappers.<StockFinancial>lambdaQuery()
                        .eq(StockFinancial::getStockId, stock.getId())
                        .orderByDesc(StockFinancial::getReportPeriod)
                        .last("LIMIT " + FINANCIAL_FETCH_LIMIT));

        LocalDate asOf = LocalDate.now();
        DividendYieldCalculator.TtmResult ttm = DividendYieldCalculator
                .ttmCashPerShare(dividends, events, asOf);
        BigDecimal cashTtm = ttm == null ? null : ttm.cashPerShare();

        PeTtmCalculator.PeTtmResult pe = quote == null
                ? new PeTtmCalculator.PeTtmResult(null, PeTtmCalculator.PeStatus.N_A,
                        "缺少总市值，无法计算 PE-TTM", null)
                : PeTtmCalculator.calculate(quote.totalMarketCap(), financials);

        // 周线五指标：本周未收盘周线的收盘价用实时价覆盖，故随行情变化（Q7-8）
        WeeklyIndicatorCalculator.WeeklyIndicators weekly = WeeklyIndicatorCalculator.calculate(
                weeklyBars,
                quote == null ? null : quote.latestPrice(),
                quote == null ? null : quote.dataTime(),
                boardProperties.getWeekly().toParams());

        return new BoardRowDto(
                stock.getStockCode(),
                stock.getStockName(),
                stock.getExchange(),
                blankToNull(stock.getIndustry()),
                quote == null ? null : quote.latestPrice(),
                quote == null ? null : quote.changePercent(),
                quote == null ? null : quote.changeAmount(),
                cashTtm == null ? null : round2(cashTtm),
                DividendYieldCalculator.yieldPercent(cashTtm,
                        quote == null ? null : quote.latestPrice()),
                PeTtmCalculator.PeStatus.LOSS == pe.status() ? null : round2(pe.pe()),
                pe.status().name(),
                weekly.maShort(),
                weekly.maLong(),
                weekly.bollUpper(),
                weekly.bollMiddle(),
                weekly.bollLower(),
                weekly.status().name(),
                weekly.lastBarDate(),
                includeTargetPrices ? TargetPriceCalculator.calculate(cashTtm, targetYields()) : null,
                quote == null ? null : quote.dataTime(),
                ttm == null ? null : ttm.dataTime(),
                latestPublishDate(financials),
                dataStatus(quote));
    }

    /**
     * 批量读取周线（一次 {@code in (stock_id)} 查询后内存分组，避免 N+1）。
     *
     * <p>只读前复权（QFQ）且来源与同步写入一致的记录；日期下界按 {@code bars-limit} 加余量收敛，
     * 分组后再截断到最近 {@code bars-limit} 根——库内可能存在早于窗口、复权基准已过期的旧行，
     * 混用会算出无意义的均线（spec 010 §十「前复权基准漂移」）。
     */
    private Map<Long, List<StockWeeklyQuote>> loadWeeklyBarsBatch(Collection<Long> stockIds) {
        if (stockIds == null || stockIds.isEmpty()) {
            return Map.of();
        }
        int barsLimit = boardProperties.getWeekly().barsLimitOrDefault();
        LocalDate since = LocalDate.now().minusWeeks(barsLimit + (long) WEEKLY_WINDOW_BUFFER_WEEKS);
        List<StockWeeklyQuote> rows = weeklyQuoteMapper.selectList(
                Wrappers.<StockWeeklyQuote>lambdaQuery()
                        .in(StockWeeklyQuote::getStockId, stockIds)
                        .eq(StockWeeklyQuote::getAdjustmentType, StockWeeklyQuote.ADJUSTMENT_QFQ)
                        .eq(StockWeeklyQuote::getSourceCode, WEEKLY_SOURCE)
                        .ge(StockWeeklyQuote::getTradeDate, since)
                        .orderByAsc(StockWeeklyQuote::getTradeDate));
        Map<Long, List<StockWeeklyQuote>> grouped = new LinkedHashMap<>();
        for (StockWeeklyQuote row : rows) {
            grouped.computeIfAbsent(row.getStockId(), key -> new ArrayList<>()).add(row);
        }
        grouped.replaceAll((stockId, bars) -> trimToLast(bars, barsLimit));
        return grouped;
    }

    /** 单只读取周线（详情/推送路径）。 */
    private List<StockWeeklyQuote> loadWeeklyBars(Long stockId) {
        return stockId == null ? List.of()
                : loadWeeklyBarsBatch(List.of(stockId)).getOrDefault(stockId, List.of());
    }

    private static List<StockWeeklyQuote> trimToLast(List<StockWeeklyQuote> bars, int limit) {
        return bars.size() <= limit ? bars
                : new ArrayList<>(bars.subList(bars.size() - limit, bars.size()));
    }

    private List<BigDecimal> targetYields() {
        List<Double> configured = boardProperties.getTargetYields();
        if (configured == null || configured.isEmpty()) {
            return TargetPriceCalculator.DEFAULT_TARGET_YIELDS;
        }
        List<BigDecimal> yields = new ArrayList<>(configured.size());
        for (Double y : configured) {
            if (y == null || y <= 0) {
                continue;
            }
            yields.add(BigDecimal.valueOf(y));
        }
        return yields.isEmpty() ? TargetPriceCalculator.DEFAULT_TARGET_YIELDS : yields;
    }

    private String dataStatus(QuoteResult quote) {
        if (quote == null || quoteService.isStale(quote)) {
            return DATA_STALE;
        }
        return quote.latestPrice() == null ? DATA_SUSPENDED : DATA_NORMAL;
    }

    private LocalDate latestPublishDate(List<StockFinancial> financials) {
        LocalDate latest = null;
        for (StockFinancial f : financials) {
            if (f.getPublishDate() != null
                    && (latest == null || f.getPublishDate().isAfter(latest))) {
                latest = f.getPublishDate();
            }
        }
        return latest;
    }

    private static BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** 行业空白归一为 null：Jackson 非空序列化后字段缺省，前端统一渲染 --（不填「其他」）。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
