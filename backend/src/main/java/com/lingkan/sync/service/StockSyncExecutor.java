package com.lingkan.sync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.datasource.provider.BatchStockDataProvider;
import com.lingkan.datasource.provider.ProviderRegistry;
import com.lingkan.datasource.provider.ProviderResult;
import com.lingkan.datasource.provider.QuoteProvider;
import com.lingkan.datasource.provider.QuoteResult;
import com.lingkan.datasource.provider.StockDataProvider;
import com.lingkan.datasource.provider.impl.EastMoneyProvider;
import com.lingkan.metrics.entity.MetricSourceBinding;
import com.lingkan.metrics.entity.StockDailyMetric;
import com.lingkan.metrics.entity.StockMetricValue;
import com.lingkan.metrics.mapper.MetricSourceBindingMapper;
import com.lingkan.metrics.mapper.StockDailyMetricMapper;
import com.lingkan.metrics.mapper.StockMetricValueMapper;
import com.lingkan.metrics.service.MetricDefinitionService;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.entity.StockQuote;
import com.lingkan.stock.mapper.StockMapper;
import com.lingkan.stock.mapper.StockQuoteMapper;
import com.lingkan.sync.StockSyncProperties;
import com.lingkan.sync.dto.StockBasicDto;
import com.lingkan.sync.entity.StockSyncTask;
import com.lingkan.sync.entity.StockSyncTaskDetail;
import com.lingkan.sync.mapper.StockSyncTaskDetailMapper;
import com.lingkan.sync.mapper.StockSyncTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 全市场同步执行器：编排"股票清单 → 行情 → 指标"三段流程。
 *
 * <p>设计要点（每条都对应一个真实约束）：
 * <ol>
 *   <li><b>清单失败即终止</b>：清单拿不到或条数异常时直接抛异常，绝不把空清单当正常全量，
 *       避免把库里所有股票误标为退市；</li>
 *   <li><b>分段独立提交</b>：每批入库单独提交，不包在超长事务里，服务重启时已成功的批次不丢；</li>
 *   <li><b>行情按批请求</b>：腾讯接口一次可带多只股票，按 100 只分批并逐批重试，单批失败不影响其他批；</li>
 *   <li><b>指标并发有界</b>：东方财富单股快照一次返回全部绑定指标，按"每股一次请求"并发执行，
 *       并发数、请求间隔、重试次数全部可配；</li>
 *   <li><b>失败隔离</b>：单股或单指标失败只记账，不中断整体流程；数据库/锁/清单这类整体性异常才终止任务；</li>
 *   <li><b>不覆盖有效数据</b>：所有写入均为 Upsert，空值不覆盖已有值（见各 Mapper 的 COALESCE 语义）。</li>
 * </ol>
 */
@Service
public class StockSyncExecutor {

    private static final Logger log = LoggerFactory.getLogger(StockSyncExecutor.class);

    public static final String TYPE_FULL = "FULL";
    public static final String TYPE_INCREMENTAL = "INCREMENTAL";
    public static final String TYPE_SINGLE = "SINGLE";
    public static final String TYPE_RETRY = "RETRY";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_PARTIAL = "PARTIAL_SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public static final String PHASE_LIST = "STOCK_LIST";
    public static final String PHASE_QUOTE = "QUOTE";
    public static final String PHASE_METRIC = "METRIC";

    public static final String DETAIL_STOCK = "STOCK";
    public static final String DETAIL_QUOTE = "QUOTE";
    public static final String DETAIL_METRIC = "METRIC";

    private final StockSyncProperties properties;
    private final StockListService stockListService;
    private final StockMapper stockMapper;
    private final StockQuoteMapper quoteMapper;
    private final StockMetricValueMapper metricValueMapper;
    private final StockDailyMetricMapper dailyMetricMapper;
    private final MetricSourceBindingMapper bindingMapper;
    private final MetricDefinitionService definitionService;
    private final ProviderRegistry registry;
    private final EastMoneyProvider eastMoneyProvider;
    private final StockSyncTaskMapper taskMapper;
    private final StockSyncTaskDetailMapper detailMapper;
    private final StockSyncLockService lockService;

    public StockSyncExecutor(StockSyncProperties properties, StockListService stockListService,
                             StockMapper stockMapper, StockQuoteMapper quoteMapper,
                             StockMetricValueMapper metricValueMapper,
                             StockDailyMetricMapper dailyMetricMapper,
                             MetricSourceBindingMapper bindingMapper,
                             MetricDefinitionService definitionService, ProviderRegistry registry,
                             EastMoneyProvider eastMoneyProvider, StockSyncTaskMapper taskMapper,
                             StockSyncTaskDetailMapper detailMapper, StockSyncLockService lockService) {
        this.properties = properties;
        this.stockListService = stockListService;
        this.stockMapper = stockMapper;
        this.quoteMapper = quoteMapper;
        this.metricValueMapper = metricValueMapper;
        this.dailyMetricMapper = dailyMetricMapper;
        this.bindingMapper = bindingMapper;
        this.definitionService = definitionService;
        this.registry = registry;
        this.eastMoneyProvider = eastMoneyProvider;
        this.taskMapper = taskMapper;
        this.detailMapper = detailMapper;
        this.lockService = lockService;
    }

    // ==================== 对外入口 ====================

    /**
     * 执行一次同步任务。所有异常都在内部消化并落到任务状态，不向外抛，
     * 避免异步线程静默失败导致任务永远停在 RUNNING。
     *
     * @param task  已创建的任务
     * @param scope 本次执行范围（指定股票 / 重试范围）
     */
    public void execute(StockSyncTask task, SyncScope scope) {
        long start = System.currentTimeMillis();
        Counters counters = new Counters(task.getId());
        task.setStatus(STATUS_RUNNING);
        task.setStartTime(LocalDateTime.now());
        task.setCurrentPhase(PHASE_LIST);
        task.setProgress(1);
        taskMapper.updateById(task);
        log.info("同步任务开始：taskNo={} type={} scope={}", task.getTaskNo(), task.getSyncType(),
                scope == null ? "ALL" : scope.describe());

        String fatal = null;
        try {
            switch (task.getSyncType()) {
                case TYPE_FULL, TYPE_INCREMENTAL -> runMarketSync(task, counters);
                case TYPE_SINGLE -> runSingle(task, counters,
                        scope == null ? List.of() : scope.stockCodes());
                case TYPE_RETRY -> runRetry(task, counters, scope);
                default -> throw new IllegalStateException("不支持的同步类型: " + task.getSyncType());
            }
        } catch (Exception ex) {
            fatal = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.error("同步任务失败：taskNo={}", task.getTaskNo(), ex);
        } finally {
            finish(task, counters, start, fatal);
            // 释放锁：即使异常也必须释放，否则后续任务会被永久阻塞
            lockService.release(task.getTaskNo());
        }
    }

    // ==================== FULL / INCREMENTAL ====================

    private void runMarketSync(StockSyncTask task, Counters counters) {
        // 1) 股票清单：失败即终止（抛异常 → 任务 FAILED），不做任何破坏性更新
        refreshStockList(task, counters);

        // 2) 查全库正常上市的股票，作为行情与指标的处理范围
        List<Stock> stocks = stockMapper.selectListedForSync();
        if (stocks.isEmpty()) {
            throw new IllegalStateException("stocks 表无正常上市股票，终止同步");
        }
        counters.totalStocks = stocks.size();

        // 3) 行情（腾讯批量）+ 4) 指标（东方财富并发）
        syncQuotesAndTencentMetrics(task, counters, stocks);
        syncEastMoneyMetrics(task, counters, stocks, null);
    }

    // ==================== SINGLE ====================

    private void runSingle(StockSyncTask task, Counters counters, List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            throw new IllegalArgumentException("指定股票同步未提供股票代码");
        }
        List<Stock> stocks = new ArrayList<>();
        for (String raw : stockCodes) {
            String code = raw == null ? null : raw.trim();
            if (code == null || code.isBlank()) {
                continue;
            }
            Stock stock = findStock(code);
            if (stock == null) {
                // 不存在则尝试补录基础信息，失败记录明细但不阻断其他代码
                Optional<StockBasicDto> dto = stockListService.fetchOne(code);
                if (dto.isEmpty()) {
                    counters.failedStocks++;
                    counters.detail(DETAIL_STOCK, code, "eastmoney", "basic",
                            "股票不存在且基础信息获取失败");
                    continue;
                }
                try {
                    stockMapper.upsertBatch(List.of(dto.get()));
                    counters.stockAdded++;
                } catch (Exception ex) {
                    counters.failedStocks++;
                    counters.detail(DETAIL_STOCK, code, "eastmoney", "basic",
                            "基础信息入库失败：" + ex.getMessage());
                    continue;
                }
                stock = findStock(code);
                if (stock == null) {
                    counters.failedStocks++;
                    counters.detail(DETAIL_STOCK, code, "eastmoney", "basic", "入库后仍查不到该股票");
                    continue;
                }
            } else {
                counters.stockSkipped++;
            }
            stocks.add(stock);
        }
        counters.totalStocks = stocks.size();
        counters.flush(PHASE_LIST, 10);
        if (stocks.isEmpty()) {
            throw new IllegalStateException("没有可同步的股票（代码均无效）");
        }
        syncQuotesAndTencentMetrics(task, counters, stocks);
        syncEastMoneyMetrics(task, counters, stocks, null);
    }

    // ==================== RETRY ====================

    /**
     * 重试：只处理源任务中失败的股票与指标，已成功的项目不会重复请求。
     */
    private void runRetry(StockSyncTask task, Counters counters, SyncScope scope) {
        if (scope == null || scope.originTaskId() == null) {
            throw new IllegalArgumentException("重试任务缺少源任务标识");
        }
        List<StockSyncTaskDetail> details = detailMapper.selectList(
                Wrappers.<StockSyncTaskDetail>lambdaQuery()
                        .eq(StockSyncTaskDetail::getTaskId, scope.originTaskId())
                        .eq(StockSyncTaskDetail::getStatus, "FAILED"));
        if (details.isEmpty()) {
            throw new IllegalStateException("源任务没有失败记录，无需重试");
        }
        Map<String, Set<String>> failedMetrics = new LinkedHashMap<>();
        Set<String> codes = new LinkedHashSet<>();
        for (StockSyncTaskDetail detail : details) {
            if (detail.getStockCode() == null) {
                continue;
            }
            codes.add(detail.getStockCode());
            if (DETAIL_METRIC.equals(detail.getSyncType()) && detail.getMetricCode() != null) {
                failedMetrics.computeIfAbsent(detail.getStockCode(), k -> new LinkedHashSet<>())
                        .add(detail.getMetricCode());
            }
        }
        if (codes.isEmpty()) {
            throw new IllegalStateException("源任务失败记录中没有可重试的股票代码");
        }

        List<Stock> stocks = stockMapper.selectByCodes(new ArrayList<>(codes));
        counters.totalStocks = stocks.size();
        counters.flush(PHASE_LIST, 10);

        // 行情重试：源任务行情失败的股票（含指标失败股票，保证基础行情可用）
        syncQuotesAndTencentMetrics(task, counters, stocks);
        // 指标重试：只补失败的指标；源任务未记录指标失败时按全部绑定指标补齐
        Set<String> metricFilter = new LinkedHashSet<>();
        failedMetrics.values().forEach(metricFilter::addAll);
        syncEastMoneyMetrics(task, counters, stocks, metricFilter.isEmpty() ? null : metricFilter);
    }

    // ==================== 股票清单 ====================

    private void refreshStockList(StockSyncTask task, Counters counters) {
        StockListService.StockListResult result = stockListService.fetchAll();
        List<StockBasicDto> stocks = result.stocks();
        int batch = Math.max(50, properties.getBatchSize());

        List<String> codes = stocks.stream().map(StockBasicDto::stockCode).toList();
        Set<String> existing = new LinkedHashSet<>();
        for (int i = 0; i < codes.size(); i += batch) {
            existing.addAll(stockMapper.selectExistingCodes(
                    codes.subList(i, Math.min(i + batch, codes.size()))));
        }

        int added = 0;
        int updated = 0;
        for (int i = 0; i < stocks.size(); i += batch) {
            List<StockBasicDto> chunk = stocks.subList(i, Math.min(i + batch, stocks.size()));
            try {
                stockMapper.upsertBatch(chunk);
                for (StockBasicDto dto : chunk) {
                    if (existing.contains(dto.stockCode())) {
                        updated++;
                    } else {
                        added++;
                    }
                }
            } catch (Exception ex) {
                // 单批失败：记录明细后继续其他批次，避免一条脏数据毁掉整次同步
                log.warn("股票基础信息第 {} 批入库失败: {}", i / batch + 1, ex.getMessage());
                for (StockBasicDto dto : chunk) {
                    counters.failedStocks++;
                    counters.detail(DETAIL_STOCK, dto.stockCode(), result.providerCode(), "basic",
                            "基础信息入库失败：" + ex.getMessage());
                }
            }
            counters.stockAdded = added;
            counters.stockUpdated = updated;
            counters.totalStocks = stocks.size();
            counters.flush(PHASE_LIST, 2 + (int) (8.0 * Math.min(i + batch, stocks.size())
                    / stocks.size()));
        }

        String source = "清单数据源=" + result.providerCode() + "，有效 " + stocks.size() + " 条，新增 "
                + added + "，更新 " + updated;
        if (result.fallbackReason() != null) {
            source = source + "；主源失败原因：" + result.fallbackReason();
        }
        task.setRemark(trim(source, 500));
        taskMapper.updateById(task);
        log.info("股票清单阶段完成：{}", source);
    }

    // ==================== 行情 + 腾讯绑定指标 ====================

    /**
     * 腾讯行情批量同步。
     *
     * <p>同一批请求的结果同时用于三处：stock_quotes（最新行情）、stock_metric_values
     * （腾讯绑定的指标值）、stock_daily_metrics（行情类指标的每日留痕）。
     * 这样"腾讯绑定的 14 项指标"不需要再额外发请求。
     */
    private void syncQuotesAndTencentMetrics(StockSyncTask task, Counters counters, List<Stock> stocks) {
        if (!properties.getQuote().isEnabled()) {
            log.info("行情同步已关闭（lingkan.stock-sync.quote.enabled=false），跳过");
            return;
        }
        Optional<QuoteProvider> providerOpt = registry.findQuoteProvider(properties.getQuote().getProvider());
        if (providerOpt.isEmpty()) {
            throw new IllegalStateException("行情适配器未注册: " + properties.getQuote().getProvider());
        }
        QuoteProvider provider = providerOpt.get();
        List<MetricSourceBinding> bindings = enabledBindings(provider.getProviderCode());
        Set<String> quoteMetricCodes = quoteCategoryMetrics();
        int batchSize = Math.max(10, properties.getQuote().getBatchSize());

        List<StockQuote> quoteBuffer = new ArrayList<>();
        List<StockMetricValue> metricBuffer = new ArrayList<>();
        List<StockDailyMetric> dailyBuffer = new ArrayList<>();
        int processed = 0;

        for (int i = 0; i < stocks.size(); i += batchSize) {
            List<Stock> chunk = stocks.subList(i, Math.min(i + batchSize, stocks.size()));
            List<String> codes = chunk.stream().map(Stock::getStockCode).toList();
            List<QuoteResult> quotes = fetchQuotesWithRetry(provider, codes);
            Map<String, QuoteResult> byCode = new HashMap<>();
            for (QuoteResult q : quotes) {
                byCode.putIfAbsent(q.stockCode(), q);
            }
            LocalDateTime fetchTime = LocalDateTime.now();

            for (Stock stock : chunk) {
                counters.quoteTotal++;
                QuoteResult quote = byCode.get(stock.getStockCode());
                String invalid = validateQuote(quote);
                if (invalid != null) {
                    counters.quoteFailed++;
                    counters.reason("行情：" + invalid);
                    counters.detail(DETAIL_QUOTE, stock.getStockCode(), provider.getProviderCode(),
                            "quote", invalid);
                    continue;
                }
                counters.quoteSuccess++;
                quoteBuffer.add(toStockQuote(stock, quote, fetchTime));
                collectQuoteMetrics(stock, quote, bindings, quoteMetricCodes, fetchTime,
                        metricBuffer, dailyBuffer, counters);
            }

            flushQuotes(quoteBuffer);
            flushMetrics(metricBuffer, dailyBuffer);
            counters.flushDetails();
            processed += chunk.size();
            int progress = 10 + (int) (35.0 * processed / Math.max(1, stocks.size()));
            counters.flush(PHASE_QUOTE, Math.min(progress, 45));
            log.info("行情进度 {}/{}：成功 {}，失败 {}", processed, stocks.size(),
                    counters.quoteSuccess, counters.quoteFailed);
        }
        log.info("行情阶段完成：总数 {}，成功 {}，失败 {}", counters.quoteTotal, counters.quoteSuccess,
                counters.quoteFailed);
    }

    private List<QuoteResult> fetchQuotesWithRetry(QuoteProvider provider, List<String> codes) {
        int maxRetries = Math.max(1, properties.getQuote().getMaxRetries());
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                List<QuoteResult> quotes = provider.fetchQuotes(codes);
                if (quotes != null && !quotes.isEmpty()) {
                    return quotes;
                }
            } catch (Exception ex) {
                log.warn("行情第 {} 次请求失败: {}", attempt, ex.getMessage());
            }
            if (attempt < maxRetries) {
                sleep((long) properties.getQuote().getRetryIntervalMs() * attempt);
            }
        }
        return List.of();
    }

    /** 行情有效性校验：返回 null 表示有效，否则返回失败原因（不覆盖已有有效行情） */
    private String validateQuote(QuoteResult quote) {
        if (quote == null) {
            return "无行情返回（可能停牌或非交易时段）";
        }
        if (quote.latestPrice() == null || quote.latestPrice().signum() <= 0) {
            return "最新价无效";
        }
        if (negative(quote.volume()) || negative(quote.amount())) {
            return "成交量或成交额为负";
        }
        if (quote.changePercent() == null && quote.changeAmount() == null) {
            return "涨跌额与涨跌幅均缺失";
        }
        return null;
    }

    private boolean negative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }

    private StockQuote toStockQuote(Stock stock, QuoteResult quote, LocalDateTime fetchTime) {
        StockQuote row = new StockQuote();
        row.setStockId(stock.getId());
        row.setSourceCode(quote.sourceCode());
        row.setLatestPrice(quote.latestPrice());
        row.setChangePercent(quote.changePercent());
        row.setChangeAmount(quote.changeAmount());
        row.setOpenPrice(quote.openPrice());
        row.setHighPrice(quote.highPrice());
        row.setLowPrice(quote.lowPrice());
        row.setPreClose(quote.preClose());
        row.setVolume(quote.volume());
        row.setAmount(quote.amount());
        row.setTurnoverRate(quote.turnoverRate());
        row.setTotalMarketCap(quote.totalMarketCap());
        row.setDataTime(quote.dataTime());
        row.setFetchTime(quote.fetchTime() == null ? fetchTime : quote.fetchTime());
        return row;
    }

    /** 把一条行情结果按绑定关系拆成指标值与每日留痕 */
    private void collectQuoteMetrics(Stock stock, QuoteResult quote,
                                     List<MetricSourceBinding> bindings, Set<String> quoteMetricCodes,
                                     LocalDateTime fetchTime, List<StockMetricValue> metricBuffer,
                                     List<StockDailyMetric> dailyBuffer, Counters counters) {
        if (bindings.isEmpty()) {
            return;
        }
        LocalDate tradeDate = quote.dataTime() == null
                ? LocalDate.now() : quote.dataTime().toLocalDate();
        for (MetricSourceBinding binding : bindings) {
            counters.metricTotal++;
            BigDecimal value = quoteValue(quote, binding.getMetricCode());
            if (value == null) {
                counters.metricFailed++;
                counters.reason("指标：" + quote.sourceCode() + " 未提供 "
                        + binding.getMetricCode());
                continue;
            }
            counters.metricSuccess++;
            metricBuffer.add(metricValue(stock, binding.getMetricCode(), quote.sourceCode(), value,
                    unitOf(binding.getMetricCode()), quote.dataTime(), fetchTime));
            if (quoteMetricCodes.contains(binding.getMetricCode())) {
                dailyBuffer.add(dailyMetric(stock, binding.getMetricCode(), value, tradeDate,
                        quote.sourceCode()));
            }
        }
    }

    private BigDecimal quoteValue(QuoteResult q, String metricCode) {
        return switch (metricCode) {
            case "latest_price" -> q.latestPrice();
            case "change_percent" -> q.changePercent();
            case "change_amount" -> q.changeAmount();
            case "open_price" -> q.openPrice();
            case "high_price" -> q.highPrice();
            case "low_price" -> q.lowPrice();
            case "pre_close" -> q.preClose();
            case "volume" -> q.volume();
            case "amount" -> q.amount();
            case "turnover_rate" -> q.turnoverRate();
            case "total_market_cap" -> q.totalMarketCap();
            case "amplitude" -> q.amplitude();
            case "circulating_market_cap" -> q.circulatingMarketCap();
            case "pb" -> q.pb();
            default -> null;
        };
    }

    // ==================== 东方财富指标 ====================

    /**
     * 东方财富指标同步。
     *
     * <p>不再"每股每指标一次请求"：使用 {@link BatchStockDataProvider#fetchMetrics}，
     * 一只股票一次请求即可拿回全部绑定指标（实测快照接口一次返回全部字段），
     * 请求量从 7 万次降到约 5000 次；并发数与请求间隔均可配，失败单股不影响其他股票。
     *
     * @param metricFilter 非 null 时只同步这些指标（重试场景）
     */
    private void syncEastMoneyMetrics(StockSyncTask task, Counters counters, List<Stock> stocks,
                                      Set<String> metricFilter) {
        if (!properties.getEastmoney().isEnabled()) {
            log.info("东方财富指标同步已关闭（lingkan.stock-sync.eastmoney.enabled=false），跳过");
            return;
        }
        List<MetricSourceBinding> allBindings = enabledBindings("eastmoney");
        List<MetricSourceBinding> bindings = metricFilter == null ? allBindings
                : allBindings.stream().filter(b -> metricFilter.contains(b.getMetricCode())).toList();
        if (bindings.isEmpty()) {
            log.warn("没有启用的东方财富指标绑定，跳过指标同步");
            return;
        }
        StockDataProvider provider = registry.find("eastmoney").orElse(null);
        if (provider == null) {
            throw new IllegalStateException("东方财富适配器未注册，无法执行指标同步");
        }
        List<String> metricCodes = bindings.stream().map(MetricSourceBinding::getMetricCode)
                .distinct().toList();
        Set<String> quoteMetricCodes = quoteCategoryMetrics();
        // 每轮开始重置熔断，先用正常请求探测数据源是否恢复
        eastMoneyProvider.resetCircuitBreaker();

        int concurrency = Math.max(1, properties.getEastmoney().getConcurrency());
        int interval = Math.max(0, properties.getEastmoney().getRequestIntervalMs());
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "lingkan-em-metric");
            t.setDaemon(true);
            return t;
        });
        CompletionService<MetricFetch> completion = new ExecutorCompletionService<>(pool);

        List<StockMetricValue> metricBuffer = new ArrayList<>();
        List<StockDailyMetric> dailyBuffer = new ArrayList<>();
        List<StockBasicDto> industryUpdates = new ArrayList<>();
        LocalDateTime fetchTime = LocalDateTime.now();

        try {
            for (Stock stock : stocks) {
                completion.submit(() -> fetchOneStockMetrics(provider, stock, metricCodes, interval));
            }
            for (int i = 0; i < stocks.size(); i++) {
                // 数据源被限流熔断后不再逐股空转：把剩余指标一次性计为失败并给出明确原因，
                // 限流恢复后可用"重试失败项"接口补齐（已成功入库的数据不回滚）。
                if (eastMoneyProvider.isCircuitOpen()) {
                    int remainingStocks = stocks.size() - i;
                    int remainingMetrics = remainingStocks * metricCodes.size();
                    counters.metricTotal += remainingMetrics;
                    counters.metricFailed += remainingMetrics;
                    String reason = "东方财富限流熔断（剩余冷却 "
                            + eastMoneyProvider.circuitRemainingSeconds() + " 秒），剩余 "
                            + remainingStocks + " 只股票共 " + remainingMetrics + " 项指标跳过，可限流恢复后重试";
                    counters.reason("指标：" + reason);
                    counters.detail(DETAIL_METRIC, stocks.get(i).getStockCode(), "eastmoney", "-", reason);
                    log.warn(reason);
                    break;
                }
                MetricFetch fetch;
                try {
                    Future<MetricFetch> future = completion.take();
                    fetch = future.get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("指标同步被中断", ex);
                } catch (Exception ex) {
                    log.warn("指标取数任务异常: {}", ex.getMessage());
                    continue;
                }
                Stock stock = fetch.stock();
                if (fetch.error() != null) {
                    counters.metricTotal += metricCodes.size();
                    counters.metricFailed += metricCodes.size();
                    counters.reason("指标：取数异常 " + fetch.error());
                    counters.detail(DETAIL_METRIC, stock.getStockCode(), "eastmoney",
                            metricCodes.getFirst(), fetch.error());
                    continue;
                }
                LocalDate tradeDate = fetch.dataTime() == null
                        ? LocalDate.now() : fetch.dataTime().toLocalDate();
                for (String metricCode : metricCodes) {
                    counters.metricTotal++;
                    ProviderResult result = fetch.results().get(metricCode);
                    BigDecimal value = result == null ? null : result.value();
                    if (value == null) {
                        counters.metricFailed++;
                        counters.reason("指标：eastmoney 未提供 " + metricCode + " "
                                + (result == null ? "无返回" : safe(result.message())));
                        if (result != null && result.status() == ProviderResult.Status.ERROR) {
                            counters.detail(DETAIL_METRIC, stock.getStockCode(), "eastmoney",
                                    metricCode, result.message());
                        }
                        continue;
                    }
                    counters.metricSuccess++;
                    metricBuffer.add(metricValue(stock, metricCode, "eastmoney", value,
                            result.unit() == null ? unitOf(metricCode) : result.unit(),
                            result.dataTime(), fetchTime));
                    if (quoteMetricCodes.contains(metricCode) && fetch.dataTime() != null) {
                        dailyBuffer.add(dailyMetric(stock, metricCode, value, tradeDate, "eastmoney"));
                    }
                }
                // 行业/上市日期补录：只针对库中缺失的股票，命中同一份缓存快照，不增加请求
                collectIndustryBackfill(stock, industryUpdates);

                if (metricBuffer.size() >= 500) {
                    flushMetrics(metricBuffer, dailyBuffer);
                }
                if (industryUpdates.size() >= 200) {
                    flushIndustry(industryUpdates);
                }
                counters.flushDetails();
                if (i % 100 == 0 || i == stocks.size() - 1) {
                    int progress = 45 + (int) (54.0 * (i + 1) / Math.max(1, stocks.size()));
                    counters.flush(PHASE_METRIC, Math.min(progress, 99));
                    log.info("指标进度 {}/{}：成功 {}，失败 {}，本股指标数 {}", i + 1, stocks.size(),
                            counters.metricSuccess, counters.metricFailed, metricCodes.size());
                }
            }
            flushMetrics(metricBuffer, dailyBuffer);
            flushIndustry(industryUpdates);
            counters.flushDetails();
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("指标阶段完成：总数 {}，成功 {}，失败 {}", counters.metricTotal, counters.metricSuccess,
                counters.metricFailed);
    }

    private void collectIndustryBackfill(Stock stock, List<StockBasicDto> target) {
        boolean lackIndustry = stock.getIndustry() == null || stock.getIndustry().isBlank();
        if (!lackIndustry && stock.getListingDate() != null) {
            return;
        }
        eastMoneyProvider.profile(stock.getStockCode()).ifPresent(profile -> {
            String industry = lackIndustry ? profile.industry() : stock.getIndustry();
            LocalDate listing = stock.getListingDate() == null
                    ? profile.listingDate() : stock.getListingDate();
            if (industry == null && listing == null) {
                return;
            }
            target.add(new StockBasicDto(stock.getStockCode(), stock.getStockName(),
                    stock.getExchange(), stock.getMarket(), stock.getBoard(), industry,
                    stock.getStockStatus() == null ? StockBasicDto.STATUS_LISTED
                            : stock.getStockStatus(),
                    Boolean.TRUE.equals(stock.getIsSt()), listing));
        });
    }

    private MetricFetch fetchOneStockMetrics(StockDataProvider provider, Stock stock,
                                             List<String> metricCodes, int interval) {
        try {
            Map<String, ProviderResult> results;
            if (provider instanceof BatchStockDataProvider batchProvider) {
                results = batchProvider.fetchMetrics(stock.getStockCode(), metricCodes);
            } else {
                results = new LinkedHashMap<>();
                for (String metricCode : metricCodes) {
                    results.put(metricCode, provider.fetch(stock.getStockCode(), metricCode));
                }
            }
            LocalDateTime dataTime = results.values().stream()
                    .map(ProviderResult::dataTime)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
            if (interval > 0) {
                sleep(interval);
            }
            return new MetricFetch(stock, results, null, dataTime);
        } catch (Exception ex) {
            return new MetricFetch(stock, Map.of(), ex.getMessage(), null);
        }
    }

    // ==================== 入库 ====================

    private void flushQuotes(List<StockQuote> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        try {
            quoteMapper.upsertBatch(buffer);
        } catch (Exception ex) {
            log.warn("行情批量入库失败（{} 条）: {}", buffer.size(), ex.getMessage());
        }
        buffer.clear();
    }

    private void flushMetrics(List<StockMetricValue> metricBuffer, List<StockDailyMetric> dailyBuffer) {
        if (!metricBuffer.isEmpty()) {
            try {
                metricValueMapper.upsertBatch(metricBuffer);
            } catch (Exception ex) {
                log.warn("指标批量入库失败（{} 条）: {}", metricBuffer.size(), ex.getMessage());
            }
            metricBuffer.clear();
        }
        if (!dailyBuffer.isEmpty()) {
            try {
                dailyMetricMapper.upsertBatch(dailyBuffer);
            } catch (Exception ex) {
                log.warn("每日指标批量入库失败（{} 条）: {}", dailyBuffer.size(), ex.getMessage());
            }
            dailyBuffer.clear();
        }
    }

    private void flushIndustry(List<StockBasicDto> updates) {
        if (updates.isEmpty()) {
            return;
        }
        try {
            stockMapper.upsertBatch(new ArrayList<>(updates));
        } catch (Exception ex) {
            log.warn("行业补录失败（{} 条）: {}", updates.size(), ex.getMessage());
        }
        updates.clear();
    }

    private StockMetricValue metricValue(Stock stock, String metricCode, String sourceCode,
                                         BigDecimal value, String unit, LocalDateTime dataTime,
                                         LocalDateTime fetchTime) {
        StockMetricValue row = new StockMetricValue();
        row.setStockId(stock.getId());
        row.setMetricCode(metricCode);
        row.setSourceCode(sourceCode);
        row.setRawValue(value.toPlainString());
        row.setNormalizedValue(value);
        row.setUnit(unit);
        row.setDataTime(dataTime);
        row.setFetchTime(fetchTime);
        row.setStatus("OK");
        return row;
    }

    private StockDailyMetric dailyMetric(Stock stock, String metricCode, BigDecimal value,
                                         LocalDate tradeDate, String sourceCode) {
        StockDailyMetric row = new StockDailyMetric();
        row.setStockId(stock.getId());
        row.setMetricCode(metricCode);
        row.setMetricValue(value);
        row.setTradeDate(tradeDate);
        row.setSourceCode(sourceCode);
        return row;
    }

    // ==================== 辅助 ====================

    private Stock findStock(String code) {
        return stockMapper.selectOne(Wrappers.<Stock>lambdaQuery()
                .eq(Stock::getStockCode, code).last("LIMIT 1"));
    }

    private List<MetricSourceBinding> enabledBindings(String providerCode) {
        return bindingMapper.selectList(Wrappers.<MetricSourceBinding>lambdaQuery()
                .eq(MetricSourceBinding::getProviderCode, providerCode)
                .eq(MetricSourceBinding::getEnabled, true)
                .orderByAsc(MetricSourceBinding::getPriority));
    }

    /**
     * 行情类指标集合：只有这类指标写"每日快照"。
     * 财务/估值类指标带报告期，不能按天伪造历史（见文档 5.5、21.4）。
     */
    private Set<String> quoteCategoryMetrics() {
        Set<String> codes = new LinkedHashSet<>();
        definitionService.listAll().stream()
                .filter(d -> "quote".equals(d.category()))
                .forEach(d -> codes.add(d.metricCode()));
        return codes;
    }

    private String unitOf(String metricCode) {
        return switch (metricCode) {
            case "change_percent", "turnover_rate", "amplitude" -> "%";
            case "volume" -> "手";
            case "volume_ratio", "pb" -> "倍";
            default -> "元";
        };
    }

    private void finish(StockSyncTask task, Counters counters, long start, String fatal) {
        long duration = System.currentTimeMillis() - start;
        StockSyncTask update = new StockSyncTask();
        update.setId(task.getId());
        update.setEndTime(LocalDateTime.now());
        update.setDurationMs(duration);
        counters.applyTo(update);
        if (fatal != null) {
            update.setStatus(STATUS_FAILED);
            update.setErrorMessage(trim(fatal, 900));
        } else {
            update.setStatus(resolveStatus(update));
        }
        update.setProgress(100);
        String remark = task.getRemark();
        if (!counters.reasons.isEmpty()) {
            String reason = counters.reasonSummary();
            remark = remark == null ? reason : remark + "；" + reason;
        }
        update.setRemark(trim(remark, 900));
        taskMapper.updateById(update);
        log.info("同步任务结束：taskNo={} status={} 耗时={}ms", task.getTaskNo(), update.getStatus(),
                duration);
    }

    /**
     * 状态判定：任一项有失败即 PARTIAL_SUCCESS；成功为 0 才算 FAILED。
     * 不能因为部分指标失败就回滚已完成的数据。
     */
    private String resolveStatus(StockSyncTask update) {
        int failed = nz(update.getQuoteFailed()) + nz(update.getMetricFailed())
                + nz(update.getFailedStocks());
        int success = nz(update.getQuoteSuccess()) + nz(update.getMetricSuccess())
                + nz(update.getSuccessStocks());
        if (failed == 0) {
            return STATUS_SUCCESS;
        }
        return success == 0 ? STATUS_FAILED : STATUS_PARTIAL;
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private String safe(String message) {
        return message == null ? "" : message;
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 指标取数任务结果 */
    private record MetricFetch(Stock stock, Map<String, ProviderResult> results, String error,
                               LocalDateTime dataTime) {
    }

    /** 执行范围：指定股票或重试范围 */
    public record SyncScope(List<String> stockCodes, Long originTaskId) {

        public static SyncScope none() {
            return new SyncScope(List.of(), null);
        }

        public static SyncScope ofStocks(List<String> codes) {
            return new SyncScope(codes == null ? List.of() : codes, null);
        }

        public static SyncScope retryOf(Long originTaskId) {
            return new SyncScope(List.of(), originTaskId);
        }

        String describe() {
            if (originTaskId != null) {
                return "RETRY(from=" + originTaskId + ")";
            }
            return stockCodes.isEmpty() ? "ALL" : "CODES(" + stockCodes.size() + ")";
        }
    }

    /**
     * 计数与明细缓冲。
     *
     * <p>为什么集中在一个对象里：进度需要周期性落库，而计数项有十几个，
     * 分散在各方法里容易漏写或写错列；这里统一映射，保证"查任务进度"看到的数就是真实进度。
     */
    private final class Counters {

        private final Long taskId;
        private final List<StockSyncTaskDetail> pendingDetails = new ArrayList<>();
        private final Map<String, Integer> reasons = new LinkedHashMap<>();

        int totalStocks;
        int successStocks;
        int failedStocks;
        int stockAdded;
        int stockUpdated;
        int stockSkipped;
        int quoteTotal;
        int quoteSuccess;
        int quoteFailed;
        int metricTotal;
        int metricSuccess;
        int metricFailed;

        Counters(Long taskId) {
            this.taskId = taskId;
        }

        void reason(String text) {
            reasons.merge(text, 1, Integer::sum);
        }

        String reasonSummary() {
            StringBuilder sb = new StringBuilder("失败原因Top3：");
            reasons.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(3)
                    .forEach(e -> sb.append(e.getKey()).append("×").append(e.getValue()).append("；"));
            return sb.toString();
        }

        void detail(String syncType, String stockCode, String providerCode, String metricCode,
                    String message) {
            if (pendingDetails.size() >= 500) {
                flushDetails();
            }
            pendingDetails.add(StockSyncTaskDetail.failed(taskId, syncType, stockCode, providerCode,
                    metricCode, message));
        }

        void flushDetails() {
            if (pendingDetails.isEmpty()) {
                return;
            }
            List<StockSyncTaskDetail> batch = new ArrayList<>(pendingDetails);
            pendingDetails.clear();
            try {
                detailMapper.insertBatch(batch);
            } catch (Exception ex) {
                // 明细写入失败不能影响主流程，但必须留痕
                log.warn("同步明细批量写入失败（{} 条）: {}", batch.size(), ex.getMessage());
            }
        }

        void flush(String phase, int progress) {
            StockSyncTask update = new StockSyncTask();
            update.setId(taskId);
            update.setCurrentPhase(phase);
            update.setProgress(progress);
            applyTo(update);
            try {
                taskMapper.updateById(update);
            } catch (Exception ex) {
                log.warn("任务进度更新失败: {}", ex.getMessage());
            }
        }

        void applyTo(StockSyncTask update) {
            update.setTotalStocks(totalStocks);
            update.setSuccessStocks(successStocks);
            update.setFailedStocks(failedStocks);
            update.setStockAdded(stockAdded);
            update.setStockUpdated(stockUpdated);
            update.setStockSkipped(stockSkipped);
            update.setQuoteTotal(quoteTotal);
            update.setQuoteSuccess(quoteSuccess);
            update.setQuoteFailed(quoteFailed);
            update.setMetricTotal(metricTotal);
            update.setMetricSuccess(metricSuccess);
            update.setMetricFailed(metricFailed);
        }
    }
}
