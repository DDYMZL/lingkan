package com.lingkan.calculation;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.calculation.dto.BoardRowDto;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.dividend.entity.StockDividend;
import com.lingkan.dividend.entity.StockShareEvent;
import com.lingkan.dividend.mapper.StockDividendMapper;
import com.lingkan.dividend.mapper.StockShareEventMapper;
import com.lingkan.financial.entity.StockFinancial;
import com.lingkan.financial.mapper.StockFinancialMapper;
import com.lingkan.metrics.service.QuoteSnapshotService;
import com.lingkan.stock.entity.Stock;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页看板指标聚合服务（spec 010 §3.5）。
 *
 * <p>单只股票的看板行 = 行情快照 + 股息率 TTM（计算）+ PE TTM（计算）+ 9 档目标价（计算）。
 * 本服务只做编排与取数，计算逻辑全部在 {@code calculation} 包的纯计算器中；
 * 计算器不反向依赖本服务（亦不依赖 metrics 模块），保持可独立单元测试。
 *
 * <p>取数范围：分红/股本事件/财报均取自同步后的本地表（stock_dividends /
 * stock_share_events / stock_financials），不在看板请求路径上直连数据源。
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

    private final QuoteSnapshotService quoteService;
    private final StockDividendMapper dividendMapper;
    private final StockShareEventMapper shareEventMapper;
    private final StockFinancialMapper financialMapper;
    private final BoardProperties boardProperties;

    public StockIndicatorService(QuoteSnapshotService quoteService,
                                 StockDividendMapper dividendMapper,
                                 StockShareEventMapper shareEventMapper,
                                 StockFinancialMapper financialMapper,
                                 BoardProperties boardProperties) {
        this.quoteService = quoteService;
        this.dividendMapper = dividendMapper;
        this.shareEventMapper = shareEventMapper;
        this.financialMapper = financialMapper;
        this.boardProperties = boardProperties;
    }

    /** 批量聚合（一次批量行情请求，避免逐只打上游） */
    public List<BoardRowDto> resolveAll(List<Stock> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return List.of();
        }
        Map<String, QuoteResult> quotes = new LinkedHashMap<>();
        quoteService.getQuotes(stocks.stream().map(Stock::getStockCode).toList())
                .forEach(q -> quotes.putIfAbsent(q.stockCode(), q));
        List<BoardRowDto> rows = new ArrayList<>(stocks.size());
        for (Stock stock : stocks) {
            rows.add(resolve(stock, quotes.get(stock.getStockCode())));
        }
        return rows;
    }

    /** 单只完整聚合；quote 可预先从批量请求传入。 */
    public BoardRowDto resolve(Stock stock, QuoteResult quote) {
        return resolveInternal(stock, quote, true);
    }

    /** 行情推送聚合：重算股息率与 PE，不重复计算不随行情变化的目标价。 */
    public BoardRowDto resolveQuoteUpdate(Stock stock, QuoteResult quote) {
        return resolveInternal(stock, quote, false);
    }

    private BoardRowDto resolveInternal(Stock stock, QuoteResult quote, boolean includeTargetPrices) {
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

        return new BoardRowDto(
                stock.getStockCode(),
                stock.getStockName(),
                stock.getExchange(),
                quote == null ? null : quote.latestPrice(),
                quote == null ? null : quote.changePercent(),
                quote == null ? null : quote.changeAmount(),
                cashTtm == null ? null : round2(cashTtm),
                DividendYieldCalculator.yieldPercent(cashTtm,
                        quote == null ? null : quote.latestPrice()),
                PeTtmCalculator.PeStatus.LOSS == pe.status() ? null : round2(pe.pe()),
                pe.status().name(),
                includeTargetPrices ? TargetPriceCalculator.calculate(cashTtm, targetYields()) : null,
                quote == null ? null : quote.dataTime(),
                ttm == null ? null : ttm.dataTime(),
                latestPublishDate(financials),
                dataStatus(quote));
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
}
