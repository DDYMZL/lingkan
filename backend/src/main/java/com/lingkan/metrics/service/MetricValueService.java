package com.lingkan.metrics.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.common.util.CalcVersion;
import com.lingkan.common.util.DecimalUtils;
import com.lingkan.datasource.provider.ProviderRegistry;
import com.lingkan.datasource.provider.ProviderResult;
import com.lingkan.datasource.provider.StockDataProvider;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.dividend.entity.StockDividend;
import com.lingkan.dividend.mapper.StockDividendMapper;
import com.lingkan.financial.entity.StockFinancial;
import com.lingkan.financial.mapper.StockFinancialMapper;
import com.lingkan.metrics.dto.MetricDefinitionDto;
import com.lingkan.metrics.dto.MetricSourceComparisonDto;
import com.lingkan.metrics.dto.MetricSourceValueDto;
import com.lingkan.metrics.dto.MetricValueDto;
import com.lingkan.metrics.entity.MetricSourceBinding;
import com.lingkan.metrics.entity.StockMetricValue;
import com.lingkan.metrics.entity.UserMetricPreference;
import com.lingkan.metrics.mapper.MetricSourceBindingMapper;
import com.lingkan.metrics.mapper.StockMetricValueMapper;
import com.lingkan.stock.entity.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标取值编排服务。
 *
 * <p>取值优先级：
 * <ol>
 *   <li>指标公式未确认（PENDING）→ 直接返回 PENDING，不输出任何数值；</li>
 *   <li>计算类指标（PE-TTM、股息率 TTM 等）→ 由本服务按已确认公式计算；</li>
 *   <li>行情类指标 → 实时行情快照；</li>
 *   <li>其他 → stock_metric_values（按用户指定来源或绑定优先级）。</li>
 * </ol>
 * 任何环节拿不到数据都返回 MISSING，禁止用 0 或估算值填充。
 */
@Service
public class MetricValueService {

    private static final Logger log = LoggerFactory.getLogger(MetricValueService.class);

    private final MetricDefinitionService definitionService;
    private final MetricPreferenceService preferenceService;
    private final QuoteSnapshotService quoteService;
    private final StockMetricValueMapper metricValueMapper;
    private final MetricSourceBindingMapper bindingMapper;
    private final StockFinancialMapper financialMapper;
    private final StockDividendMapper dividendMapper;
    private final ProviderRegistry registry;

    public MetricValueService(MetricDefinitionService definitionService,
                              MetricPreferenceService preferenceService,
                              QuoteSnapshotService quoteService,
                              StockMetricValueMapper metricValueMapper,
                              MetricSourceBindingMapper bindingMapper,
                              StockFinancialMapper financialMapper,
                              StockDividendMapper dividendMapper,
                              ProviderRegistry registry) {
        this.definitionService = definitionService;
        this.preferenceService = preferenceService;
        this.quoteService = quoteService;
        this.metricValueMapper = metricValueMapper;
        this.bindingMapper = bindingMapper;
        this.financialMapper = financialMapper;
        this.dividendMapper = dividendMapper;
        this.registry = registry;
    }

    /** 解析单只股票的全部启用指标 */
    public Map<String, MetricValueDto> resolveMetrics(Stock stock, String userId) {
        QuoteResult quote = quoteService.getQuote(stock.getStockCode()).orElse(null);
        return resolveMetrics(stock, userId, quote);
    }

    private Map<String, MetricValueDto> resolveMetrics(Stock stock, String userId, QuoteResult quote) {
        List<UserMetricPreference> preferences = preferenceService.list(userId);
        Map<String, MetricValueDto> result = new LinkedHashMap<>();
        for (UserMetricPreference pref : preferences) {
            if (!Boolean.TRUE.equals(pref.getEnabled())) {
                continue;
            }
            result.put(pref.getMetricCode(), resolveOne(stock, pref, quote));
        }
        return result;
    }

    /**
     * 批量解析：一次请求拿到整页股票的行情，避免逐只请求上游。
     */
    public Map<String, Map<String, MetricValueDto>> resolveMetricsBatch(List<Stock> stocks,
                                                                        String userId) {
        if (stocks == null || stocks.isEmpty()) {
            return Map.of();
        }
        Map<String, QuoteResult> quotes = new LinkedHashMap<>();
        quoteService.getQuotes(stocks.stream().map(Stock::getStockCode).toList())
                .forEach(q -> quotes.putIfAbsent(q.stockCode(), q));
        Map<String, Map<String, MetricValueDto>> result = new LinkedHashMap<>();
        for (Stock stock : stocks) {
            result.put(stock.getStockCode(),
                    resolveMetrics(stock, userId, quotes.get(stock.getStockCode())));
        }
        return result;
    }

    private MetricValueDto resolveOne(Stock stock, UserMetricPreference pref, QuoteResult quote) {
        MetricDefinitionDto def = definitionService.get(pref.getMetricCode());
        String unit = resolveUnit(pref, def);
        int precision = pref.getPrecision() == null ? def.defaultPrecision() : pref.getPrecision();
        String name = pref.getDisplayName() == null || pref.getDisplayName().isBlank()
                ? def.metricName() : pref.getDisplayName();

        if ("PENDING".equals(def.calculationStatus())) {
            return MetricValueDto.pending(def.metricCode(), name, unit,
                    "公式待确认：" + (def.pendingQuestions() == null ? "口径未确认" : def.pendingQuestions()));
        }

        // 1) 计算类指标
        MetricValueDto calculated = calculate(stock, def, unit, precision, name, quote);
        if (calculated != null) {
            return calculated;
        }

        // 2) 行情快照类指标
        MetricValueDto fromQuote = fromQuote(stock, def, quote, unit, precision, name);
        if (fromQuote != null) {
            return fromQuote;
        }

        // 3) 指标值表（多来源）
        StockMetricValue stored = findStoredValue(stock.getId(), def.metricCode(), pref.getSelectedSource());
        if (stored != null) {
            return new MetricValueDto(def.metricCode(), name, unit, precision,
                    stored.getNormalizedValue(),
                    format(stored.getNormalizedValue(), precision),
                    stored.getStatus() == null ? "OK" : stored.getStatus(),
                    null,
                    stored.getSourceCode(),
                    providerName(stored.getSourceCode()),
                    stored.getDataTime(),
                    stored.getFetchTime(),
                    stored.getCalculationVersion());
        }

        return MetricValueDto.missing(def.metricCode(), name, unit, String.valueOf(precision),
                "暂无数据");
    }

    /**
     * 已确认公式的计算指标。数据不足时返回 missing 结果（而非 null），以便前端展示明确状态。
     */
    private MetricValueDto calculate(Stock stock, MetricDefinitionDto def, String unit, int precision,
                                     String name, QuoteResult quote) {
        if (!"CALCULATED".equals(def.calculationType())) {
            return null;
        }
        return switch (def.metricCode()) {
            case "pe_ttm" -> peTtm(stock, def, unit, precision, name, quote);
            case "dividend_yield_ttm" -> dividendYieldTtm(stock, def, unit, precision, name, quote);
            default -> null;
        };
    }

    private MetricValueDto peTtm(Stock stock, MetricDefinitionDto def, String unit, int precision,
                                 String name, QuoteResult quote) {
        BigDecimal marketCap = quote == null ? null : quote.totalMarketCap();
        if (marketCap == null) {
            return MetricValueDto.missing(def.metricCode(), name, unit, String.valueOf(precision),
                    "缺少总市值，无法计算 PE-TTM");
        }
        BigDecimal profitTtm = sumParentNetProfitTtm(stock.getId());
        if (profitTtm == null) {
            return MetricValueDto.missing(def.metricCode(), name, unit, String.valueOf(precision),
                    "财务数据未采集，无法计算 PE-TTM");
        }
        if (profitTtm.signum() == 0) {
            return MetricValueDto.missing(def.metricCode(), name, unit, String.valueOf(precision),
                    "归母净利润为 0，PE-TTM 不可计算");
        }
        BigDecimal pe = DecimalUtils.divide(marketCap, profitTtm);
        String status = profitTtm.signum() < 0 ? "OK" : "OK";
        String statusText = profitTtm.signum() < 0 ? "亏损（不参与低 PE 排名）" : null;
        return new MetricValueDto(def.metricCode(), name, unit, precision,
                pe == null ? null : DecimalUtils.round(pe, precision),
                format(pe, precision), status, statusText,
                "system_calc", "系统计算", LocalDate.now().atStartOfDay(), LocalDateTime.now(),
                CalcVersion.PE_TTM_V1);
    }

    private MetricValueDto dividendYieldTtm(Stock stock, MetricDefinitionDto def, String unit,
                                            int precision, String name, QuoteResult quote) {
        BigDecimal price = quote == null ? null : quote.latestPrice();
        if (price == null) {
            return MetricValueDto.missing(def.metricCode(), name, unit, String.valueOf(precision),
                    "缺少最新价，无法计算股息率");
        }
        BigDecimal cashTtm = sumCashDividendTtm(stock.getId());
        if (cashTtm == null) {
            return MetricValueDto.missing(def.metricCode(), name, unit, String.valueOf(precision),
                    "分红数据未采集，无法计算股息率");
        }
        BigDecimal yield = DecimalUtils.percent(cashTtm, price);
        return new MetricValueDto(def.metricCode(), name, unit, precision,
                yield == null ? null : DecimalUtils.round(yield, precision),
                format(yield, precision), "OK", null,
                "system_calc", "系统计算", LocalDate.now().atStartOfDay(), LocalDateTime.now(),
                CalcVersion.DIVIDEND_YIELD_TTM_V1);
    }

    /** 最近四个季度归母净利润之和；不足四个季度返回 null（不做过早估算） */
    private BigDecimal sumParentNetProfitTtm(Long stockId) {
        List<StockFinancial> financials = financialMapper.selectList(
                Wrappers.<StockFinancial>lambdaQuery()
                        .eq(StockFinancial::getStockId, stockId)
                        .orderByDesc(StockFinancial::getReportPeriod)
                        .last("LIMIT 4"));
        if (financials.size() < 4) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (StockFinancial f : financials) {
            if (f.getNetProfitParent() == null) {
                return null;
            }
            sum = sum.add(f.getNetProfitParent());
        }
        return sum;
    }

    /** 过去 12 个月已实施的每股现金分红之和 */
    private BigDecimal sumCashDividendTtm(Long stockId) {
        LocalDate from = LocalDate.now().minusDays(365);
        List<StockDividend> dividends = dividendMapper.selectList(
                Wrappers.<StockDividend>lambdaQuery()
                        .eq(StockDividend::getStockId, stockId)
                        .ge(StockDividend::getExDividendDate, from));
        if (dividends.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (StockDividend d : dividends) {
            if (d.getCashDividendPerShare() == null) {
                continue;
            }
            String status = d.getPlanStatus();
            if (status != null && (status.contains("预案") || status.contains("未实施"))) {
                continue;
            }
            sum = sum.add(d.getCashDividendPerShare());
        }
        return sum;
    }

    private MetricValueDto fromQuote(Stock stock, MetricDefinitionDto def, QuoteResult quote,
                                     String unit, int precision, String name) {
        if (quote == null) {
            return null;
        }
        BigDecimal value = switch (def.metricCode()) {
            case "latest_price" -> quote.latestPrice();
            case "change_percent" -> quote.changePercent();
            case "change_amount" -> quote.changeAmount();
            case "open_price" -> quote.openPrice();
            case "high_price" -> quote.highPrice();
            case "low_price" -> quote.lowPrice();
            case "pre_close" -> quote.preClose();
            case "volume" -> quote.volume();
            case "amount" -> quote.amount();
            case "turnover_rate" -> quote.turnoverRate();
            case "total_market_cap" -> quote.totalMarketCap();
            default -> null;
        };
        if (value == null) {
            return null;
        }
        return new MetricValueDto(def.metricCode(), name, unit, precision,
                DecimalUtils.round(value, precision), format(value, precision), "OK", null,
                quote.sourceCode(), providerName(quote.sourceCode()),
                quote.dataTime(), quote.fetchTime(), null);
    }

    /**
     * 多数据源对比：并列展示各来源取值与差异，不做平均。
     */
    public MetricSourceComparisonDto compare(Stock stock, String metricCode, String userId,
                                            String stockName) {
        MetricDefinitionDto def = definitionService.get(metricCode);
        List<MetricSourceBinding> bindings = bindingMapper.selectList(
                Wrappers.<MetricSourceBinding>lambdaQuery()
                        .eq(MetricSourceBinding::getMetricCode, metricCode)
                        .orderByAsc(MetricSourceBinding::getPriority));
        String selectedSource = preferenceService.list(userId).stream()
                .filter(p -> p.getMetricCode().equals(metricCode))
                .map(UserMetricPreference::getSelectedSource)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElseGet(() -> bindings.stream()
                        .filter(b -> Boolean.TRUE.equals(b.getIsPrimary()))
                        .map(MetricSourceBinding::getProviderCode)
                        .findFirst()
                        .orElse(null));

        List<MetricSourceValueDto> sources = new ArrayList<>();
        BigDecimal primaryValue = null;
        for (MetricSourceBinding binding : bindings) {
            if (!Boolean.TRUE.equals(binding.getEnabled())) {
                continue;
            }
            StockDataProvider provider = registry.find(binding.getProviderCode()).orElse(null);
            if (provider == null) {
                continue;
            }
            ProviderResult pr = provider.fetch(stock.getStockCode(), metricCode);
            boolean isPrimary = binding.getProviderCode().equals(selectedSource);
            if (isPrimary) {
                primaryValue = pr.value();
            }
            sources.add(new MetricSourceValueDto(
                    binding.getProviderCode(), provider.getProviderName(),
                    pr.value(), format(pr.value(), def.defaultPrecision()),
                    pr.unit() == null ? def.unit() : pr.unit(),
                    pr.dataTime(), pr.fetchTime(),
                    pr.status().name(), null, isPrimary));
        }

        // 系统计算来源（计算类指标）
        MetricValueDto computed = resolveOne(stock,
                preferenceService.list(userId).stream()
                        .filter(p -> p.getMetricCode().equals(metricCode))
                        .findFirst()
                        .orElseGet(() -> defaultPreference(metricCode)),
                quoteService.getQuote(stock.getStockCode()).orElse(null));
        if ("system_calc".equals(computed.sourceCode())) {
            BigDecimal computedValue = computed.value() instanceof BigDecimal bd ? bd : null;
            if (selectedSource == null) {
                primaryValue = computedValue;
            }
            sources.add(new MetricSourceValueDto("system_calc", "系统计算",
                    computedValue, computed.displayValue(), computed.unit(),
                    computed.dataTime(), computed.fetchTime(), computed.status(),
                    null, "system_calc".equals(selectedSource)));
        }

        List<MetricSourceValueDto> withDiff = new ArrayList<>(sources.size());
        for (MetricSourceValueDto s : sources) {
            BigDecimal diff = null;
            if (primaryValue != null && s.value() instanceof BigDecimal v) {
                diff = v.subtract(primaryValue).setScale(4, RoundingMode.HALF_UP);
            }
            withDiff.add(new MetricSourceValueDto(s.sourceCode(), s.sourceName(), s.value(),
                    s.displayValue(), s.unit(), s.dataTime(), s.fetchTime(), s.status(), diff,
                    s.primary()));
        }

        return new MetricSourceComparisonDto(stock.getStockCode(), stockName, metricCode,
                def.metricName(), def.unit(), selectedSource, withDiff);
    }

    private UserMetricPreference defaultPreference(String metricCode) {
        UserMetricPreference pref = new UserMetricPreference();
        pref.setMetricCode(metricCode);
        pref.setEnabled(true);
        return pref;
    }

    private StockMetricValue findStoredValue(Long stockId, String metricCode, String selectedSource) {
        var query = Wrappers.<StockMetricValue>lambdaQuery()
                .eq(StockMetricValue::getStockId, stockId)
                .eq(StockMetricValue::getMetricCode, metricCode);
        if (selectedSource != null && !selectedSource.isBlank()) {
            query.eq(StockMetricValue::getSourceCode, selectedSource);
        } else {
            query.orderByDesc(StockMetricValue::getFetchTime);
        }
        query.last("LIMIT 1");
        return metricValueMapper.selectOne(query);
    }

    private String providerName(String providerCode) {
        return registry.find(providerCode).map(StockDataProvider::getProviderName)
                .orElse(providerCode == null ? "" : providerCode);
    }

    private String resolveUnit(UserMetricPreference pref, MetricDefinitionDto def) {
        if (pref.getUnit() != null && !pref.getUnit().isBlank()) {
            return pref.getUnit();
        }
        return def.unit() == null ? "" : def.unit();
    }

    /** 统一格式化：缺失输出 "--"，数值按精度四舍五入并按需要追加单位 */
    public String format(BigDecimal value, int precision) {
        if (value == null) {
            return "--";
        }
        return value.setScale(precision, RoundingMode.HALF_UP).toPlainString();
    }
}
