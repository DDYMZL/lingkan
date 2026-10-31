package com.lingkan.sync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.calculation.BoardProperties;
import com.lingkan.common.config.AsyncConfig;
import com.lingkan.common.exception.BizException;
import com.lingkan.common.response.ApiCode;
import com.lingkan.datasource.entity.DataSyncLog;
import com.lingkan.datasource.mapper.DataSyncLogMapper;
import com.lingkan.datasource.provider.WeeklyKlineProvider;
import com.lingkan.datasource.provider.dto.WeeklyBar;
import com.lingkan.datasource.provider.impl.EastMoneyWeeklyKlineProvider;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.entity.StockWeeklyQuote;
import com.lingkan.stock.mapper.StockMapper;
import com.lingkan.stock.mapper.StockWeeklyQuoteMapper;
import com.lingkan.sync.dto.SyncAcceptVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 周线（周 K）同步服务（spec 010 §4.4 / design §4.4）。
 *
 * <p>职责：把东财周 K（{@code klt=102}、前复权 {@code fqt=1}）写入
 * {@code stock_weekly_quotes}，并写 {@code data_sync_logs}（{@code sync_type = WEEKLY_KLINE}）留痕。
 * 看板与推送路径只读该表，不在请求链路上打上游（FR-10.14）。
 *
 * <p>触发方式：
 * <ol>
 *   <li>交易日按 {@code lingkan.board.weekly.refresh-interval-ms}（默认 60 分钟）定时
 *       （见 {@code WeeklyKlineSyncScheduler}，自选股范围由调度器提供）；</li>
 *   <li>添加自选成功后异步即时同步（见 {@code WatchlistService.add}）；</li>
 *   <li>手动接口 {@code POST /api/v1/sync/weekly-kline}。</li>
 * </ol>
 *
 * <p><b>整窗口覆盖 upsert</b>：前复权以最新除权事件为基准，每次除权除息后全部历史前复权值整体重算，
 * 因此每次都覆盖最近 {@code bars-limit}（默认 130）根，禁止只增量追加，否则表内会混用两套复权基准。
 * 已存在的行仅在数值确有变化时才 UPDATE（避免每小时 130 次无意义写入），
 * {@code fetch_time} 随变化一并刷新，作为该行数据的采集时间留痕（铁律 4）。
 *
 * <p>互斥与降级：同一时刻只跑一个批次（{@code running} 原子标志），定时与手动提交被拒绝时
 * 返回 {@code accepted=false} 不排队，添加自选触发的即时同步则有界重试
 * （见 {@link ImmediateSyncRetry}）；单只失败只记日志并保留库内旧数据，周线列显示 {@code --}，
 * 不影响其余列与整页。
 */
@Service
public class WeeklyKlineSyncService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyKlineSyncService.class);

    public static final String SYNC_TYPE = "WEEKLY_KLINE";
    private static final String PROVIDER_CODE = EastMoneyWeeklyKlineProvider.CODE;

    private final WeeklyKlineProvider weeklyKlineProvider;
    private final StockWeeklyQuoteMapper weeklyQuoteMapper;
    private final StockMapper stockMapper;
    private final DataSyncLogMapper syncLogMapper;
    private final BoardProperties boardProperties;
    private final Executor dataSyncExecutor;

    /** 批次互斥：同一时刻只跑一个周线同步批次（单只即时同步同样受控） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public WeeklyKlineSyncService(WeeklyKlineProvider weeklyKlineProvider,
                                  StockWeeklyQuoteMapper weeklyQuoteMapper,
                                  StockMapper stockMapper,
                                  DataSyncLogMapper syncLogMapper,
                                  BoardProperties boardProperties,
                                  @Qualifier(AsyncConfig.DATA_SYNC_EXECUTOR) Executor dataSyncExecutor) {
        this.weeklyKlineProvider = weeklyKlineProvider;
        this.weeklyQuoteMapper = weeklyQuoteMapper;
        this.stockMapper = stockMapper;
        this.syncLogMapper = syncLogMapper;
        this.boardProperties = boardProperties;
        this.dataSyncExecutor = dataSyncExecutor;
    }

    // ==================== 提交入口 ====================

    /**
     * 批量提交（定时任务与手动全量入口共用）；范围为空时返回「无需同步」而不抛异常，
     * 保证自选为空时调度器不会刷错误日志。
     */
    public SyncAcceptVo submitAll(List<String> stockCodes, String triggerType) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return SyncAcceptVo.accepted(nextJobId(), 0, "自选股为空，无需同步");
        }
        return submitForStocks(stockCodes, triggerType);
    }

    /** 同步指定股票；代码为空或不存在时抛 {@link BizException}。 */
    public SyncAcceptVo submitForStocks(List<String> stockCodes, String triggerType) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            throw new BizException(ApiCode.BAD_REQUEST, "stockCodes 不能为空");
        }
        List<String> codes = stockCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
        if (codes.isEmpty()) {
            throw new BizException(ApiCode.BAD_REQUEST, "stockCodes 不能为空");
        }
        List<Stock> stocks = stockMapper.selectByCodes(codes);
        Set<String> found = new HashSet<>();
        stocks.forEach(stock -> found.add(stock.getStockCode()));
        List<String> missing = codes.stream().filter(code -> !found.contains(code)).toList();
        if (!missing.isEmpty()) {
            throw new BizException(ApiCode.BAD_REQUEST, "股票不存在: " + String.join(",", missing));
        }
        if (!running.compareAndSet(false, true)) {
            return SyncAcceptVo.rejected("周线同步正在执行，本次提交被跳过");
        }
        String jobId = nextJobId();
        dataSyncExecutor.execute(() -> {
            try {
                runSync(stocks, triggerType);
            } finally {
                running.set(false);
            }
        });
        return SyncAcceptVo.accepted(jobId, stocks.size(),
                "已提交 " + stocks.size() + " 只股票的周线同步");
    }

    /**
     * 即时同步（添加自选等内部触发）：受理失败只记日志不抛出，
     * 保证业务流程（添加自选）不被同步问题打断。
     *
     * <p>批次互斥拒绝时在异步线程里有界重试（见 {@link ImmediateSyncRetry}），
     * 避免连续添加多只自选时后加的股票周线列长时间显示 {@code --}。
     */
    public void syncAsync(List<String> stockCodes, String triggerType) {
        ImmediateSyncRetry.run(dataSyncExecutor, log, "周线", triggerType,
                () -> submitForStocks(stockCodes, triggerType));
    }

    private String nextJobId() {
        return "wk-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ==================== 执行 ====================

    private void runSync(List<Stock> stocks, String triggerType) {
        log.info("周线同步开始：{} 只股票，触发方式 {}", stocks.size(), triggerType);
        DataSyncLog syncLog = startLog(stocks.size());
        int success = 0;
        int failed = 0;
        for (int i = 0; i < stocks.size(); i++) {
            if (safeSync(stocks.get(i))) {
                success++;
            } else {
                failed++;
            }
            if (i < stocks.size() - 1) {
                sleepBetweenRequests();
            }
        }
        finishLog(syncLog, success, failed);
        log.info("周线同步结束：成功 {}/{}", success, stocks.size());
    }

    private boolean safeSync(Stock stock) {
        try {
            return syncStockWeekly(stock);
        } catch (Exception ex) {
            log.error("股票 {} 周线同步异常: {}", stock.getStockCode(), ex.getMessage());
            return false;
        }
    }

    /**
     * 同步单只股票的周线。
     *
     * @return false 表示数据源取数失败（MISSING，保留库内旧数据），true 表示取数成功（含 0 根）
     */
    boolean syncStockWeekly(Stock stock) {
        int barsLimit = boardProperties.getWeekly().barsLimitOrDefault();
        List<WeeklyBar> bars = weeklyKlineProvider.fetchWeeklyKline(stock.getStockCode(), barsLimit);
        if (bars == null) {
            return false;
        }
        if (bars.isEmpty()) {
            log.debug("股票 {} 周线为空（新上市或数据源无周 K）", stock.getStockCode());
            return true;
        }
        int[] counts = upsertBars(stock.getId(), bars);
        log.debug("股票 {} 周线同步：{} 根（新增 {}，更新 {}）",
                stock.getStockCode(), bars.size(), counts[0], counts[1]);
        return true;
    }

    /**
     * 整窗口覆盖 upsert：按 {@code (stock_id, trade_date, adjustment_type, source_code)} 先查后插/更新。
     * openGauss 不使用 {@code ON CONFLICT}，与分红/财务同步保持同一套写法。
     *
     * @return {@code [新增条数, 更新条数]}
     */
    private int[] upsertBars(Long stockId, List<WeeklyBar> bars) {
        List<StockWeeklyQuote> existing = weeklyQuoteMapper.selectList(
                Wrappers.<StockWeeklyQuote>lambdaQuery()
                        .eq(StockWeeklyQuote::getStockId, stockId)
                        .eq(StockWeeklyQuote::getAdjustmentType, StockWeeklyQuote.ADJUSTMENT_QFQ)
                        .eq(StockWeeklyQuote::getSourceCode, PROVIDER_CODE));
        Map<LocalDate, StockWeeklyQuote> byDate = new HashMap<>();
        existing.forEach(row -> byDate.put(row.getTradeDate(), row));

        LocalDateTime fetchTime = LocalDateTime.now();
        int inserted = 0;
        int updated = 0;
        for (WeeklyBar bar : bars) {
            if (bar == null || bar.tradeDate() == null || bar.close() == null) {
                continue;
            }
            StockWeeklyQuote row = byDate.get(bar.tradeDate());
            if (row == null) {
                row = new StockWeeklyQuote();
                row.setStockId(stockId);
                row.setTradeDate(bar.tradeDate());
                row.setAdjustmentType(StockWeeklyQuote.ADJUSTMENT_QFQ);
                row.setSourceCode(PROVIDER_CODE);
                applyBar(row, bar, fetchTime);
                weeklyQuoteMapper.insert(row);
                byDate.put(bar.tradeDate(), row);
                inserted++;
            } else if (applyBar(row, bar, fetchTime)) {
                weeklyQuoteMapper.updateById(row);
                updated++;
            }
        }
        return new int[]{inserted, updated};
    }

    /** 应用周线字段；返回是否有变化（决定是否 update，未变化时不刷 fetch_time）。 */
    private boolean applyBar(StockWeeklyQuote row, WeeklyBar bar, LocalDateTime fetchTime) {
        boolean changed = !sameDecimal(row.getOpenPrice(), bar.open())
                || !sameDecimal(row.getClosePrice(), bar.close())
                || !sameDecimal(row.getHighPrice(), bar.high())
                || !sameDecimal(row.getLowPrice(), bar.low())
                || !sameDecimal(row.getVolume(), bar.volume())
                || !sameDecimal(row.getAmount(), bar.amount());
        if (!changed) {
            return false;
        }
        row.setOpenPrice(bar.open());
        row.setClosePrice(bar.close());
        row.setHighPrice(bar.high());
        row.setLowPrice(bar.low());
        row.setVolume(bar.volume());
        row.setAmount(bar.amount());
        row.setFetchTime(fetchTime);
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(fetchTime);
        }
        return true;
    }

    // ==================== 日志与限流 ====================

    private DataSyncLog startLog(int total) {
        DataSyncLog entry = new DataSyncLog();
        entry.setProviderCode(PROVIDER_CODE);
        entry.setSyncType(SYNC_TYPE);
        entry.setStartedAt(LocalDateTime.now());
        entry.setTotalCount(total);
        entry.setSyncStatus("RUNNING");
        syncLogMapper.insert(entry);
        return entry;
    }

    private void finishLog(DataSyncLog entry, int success, int failed) {
        entry.setSuccessCount(success);
        entry.setFailedCount(failed);
        entry.setFinishedAt(LocalDateTime.now());
        entry.setSyncStatus(failed == 0 ? "SUCCESS" : success == 0 ? "FAILED" : "PARTIAL");
        entry.setErrorMessage(failed > 0 ? failed + " 只股票周线同步失败（接口失败或异常，见应用日志）" : null);
        syncLogMapper.updateById(entry);
    }

    private void sleepBetweenRequests() {
        int interval = boardProperties.getWeekly().getRequestIntervalMs();
        if (interval <= 0) {
            return;
        }
        try {
            Thread.sleep(interval);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** BigDecimal 数值相等（忽略标度，避免 11.6 vs 11.60 误判为变化）。 */
    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.compareTo(right) == 0;
    }
}
