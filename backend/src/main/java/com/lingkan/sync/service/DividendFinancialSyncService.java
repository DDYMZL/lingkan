package com.lingkan.sync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.calculation.DividendStatusRules;
import com.lingkan.common.config.AsyncConfig;
import com.lingkan.common.exception.BizException;
import com.lingkan.common.response.ApiCode;
import com.lingkan.datasource.entity.DataSyncLog;
import com.lingkan.datasource.mapper.DataSyncLogMapper;
import com.lingkan.datasource.provider.DividendProvider;
import com.lingkan.datasource.provider.FinancialProvider;
import com.lingkan.datasource.provider.dto.DividendItem;
import com.lingkan.datasource.provider.dto.FinancialItem;
import com.lingkan.datasource.provider.impl.EastMoneyDividendProvider;
import com.lingkan.dividend.entity.StockDividend;
import com.lingkan.dividend.entity.StockShareEvent;
import com.lingkan.dividend.mapper.StockDividendMapper;
import com.lingkan.dividend.mapper.StockShareEventMapper;
import com.lingkan.financial.entity.StockFinancial;
import com.lingkan.financial.mapper.StockFinancialMapper;
import com.lingkan.stock.entity.Stock;
import com.lingkan.stock.mapper.StockMapper;
import com.lingkan.sync.DividendFinancialSyncProperties;
import com.lingkan.sync.dto.SyncAcceptVo;
import com.lingkan.watchlist.entity.UserWatchlist;
import com.lingkan.watchlist.mapper.UserWatchlistMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分红/财务同步服务（spec 010 §4.3）。
 *
 * <p>职责：把东财分红方案写入 {@code stock_dividends} 与 {@code stock_share_events}，
 * 把财报写入 {@code stock_financials}，并写 {@code data_sync_logs} 留痕。
 *
 * <p>触发方式：
 * <ol>
 *   <li>每日 05:30 定时（自选股增量，见 {@code DividendFinancialSyncScheduler}）；</li>
 *   <li>添加自选成功后异步即时同步（见 {@code WatchlistService.add}）；</li>
 *   <li>手动接口 {@code POST /api/v1/sync/dividend-financial}。</li>
 * </ol>
 *
 * <p>去重与演进：
 * <ul>
 *   <li>分红按 {@code dividend_id} 优先匹配，其次按「归属年度 + 除息日」，
 *       再按「归属年度 + 公告日」兜底——同一方案从「预案」演进到「实施」时
 *       （除息日从无到有、dividend_id 锚点变化）仍匹配到原记录并原地更新；</li>
 *   <li>财报按「报告期 + source_code」upsert；</li>
 *   <li>股本事件仅对「已实施且除息日存在且比例 &gt; 0」的方案生成。</li>
 * </ul>
 *
 * <p>互斥：同一时刻只允许一个同步批次在跑（{@code running} 原子标志），
 * 定时与手动提交被拒绝时返回 {@code accepted=false} 不排队，添加自选触发的即时同步
 * 则有界重试（见 {@link ImmediateSyncRetry}）；入库操作本身幂等。
 */
@Service
public class DividendFinancialSyncService {

    private static final Logger log = LoggerFactory.getLogger(DividendFinancialSyncService.class);

    public static final String SYNC_TYPE_DIVIDEND = "DIVIDEND";
    public static final String SYNC_TYPE_FINANCIAL = "FINANCIAL";
    private static final String PROVIDER_CODE = EastMoneyDividendProvider.CODE;

    private final DividendProvider dividendProvider;
    private final FinancialProvider financialProvider;
    private final StockDividendMapper dividendMapper;
    private final StockFinancialMapper financialMapper;
    private final StockShareEventMapper shareEventMapper;
    private final StockMapper stockMapper;
    private final UserWatchlistMapper watchlistMapper;
    private final DataSyncLogMapper syncLogMapper;
    private final DividendFinancialSyncProperties properties;
    private final Executor dataSyncExecutor;

    /** 批次互斥：同一时刻只跑一个同步批次（单只股票的即时同步同样受控） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DividendFinancialSyncService(DividendProvider dividendProvider,
                                        FinancialProvider financialProvider,
                                        StockDividendMapper dividendMapper,
                                        StockFinancialMapper financialMapper,
                                        StockShareEventMapper shareEventMapper,
                                        StockMapper stockMapper,
                                        UserWatchlistMapper watchlistMapper,
                                        DataSyncLogMapper syncLogMapper,
                                        DividendFinancialSyncProperties properties,
                                        @Qualifier(AsyncConfig.DATA_SYNC_EXECUTOR) Executor dataSyncExecutor) {
        this.dividendProvider = dividendProvider;
        this.financialProvider = financialProvider;
        this.dividendMapper = dividendMapper;
        this.financialMapper = financialMapper;
        this.shareEventMapper = shareEventMapper;
        this.stockMapper = stockMapper;
        this.watchlistMapper = watchlistMapper;
        this.syncLogMapper = syncLogMapper;
        this.properties = properties;
        this.dataSyncExecutor = dataSyncExecutor;
    }

    // ==================== 提交入口 ====================

    /** 同步全部自选股（定时任务与手动全量共用）。 */
    public SyncAcceptVo submitForWatchlist(String triggerType) {
        List<String> codes = watchlistStockCodes();
        if (codes.isEmpty()) {
            return SyncAcceptVo.accepted(nextJobId(), 0, "自选股为空，无需同步");
        }
        return submit(codes, triggerType);
    }

    /** 同步指定股票；代码不存在时抛 {@link BizException}。 */
    public SyncAcceptVo submitForStocks(List<String> stockCodes, String triggerType) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            throw new BizException(ApiCode.BAD_REQUEST, "stockCodes 不能为空");
        }
        List<String> codes = stockCodes.stream().map(String::trim).filter(c -> !c.isEmpty()).toList();
        if (codes.isEmpty()) {
            throw new BizException(ApiCode.BAD_REQUEST, "stockCodes 不能为空");
        }
        return submit(codes, triggerType);
    }

    /**
     * 即时同步（添加自选等内部触发）：受理失败只记日志不抛出，
     * 保证业务流程（添加自选）不被同步问题打断。
     *
     * <p>批次互斥拒绝时在异步线程里有界重试（见 {@link ImmediateSyncRetry}）：
     * 分红/财报定时同步每日只跑一次，若连续添加自选时直接丢弃，后加的股票
     * 股息率与 PE 会一直显示 {@code --} 到次日凌晨。
     */
    public void syncAsync(List<String> stockCodes, String triggerType) {
        ImmediateSyncRetry.run(dataSyncExecutor, log, "分红/财务", triggerType,
                () -> submitForStocks(stockCodes, triggerType));
    }

    private SyncAcceptVo submit(List<String> codes, String triggerType) {
        List<Stock> stocks = stockMapper.selectByCodes(codes);
        Set<String> found = new HashSet<>();
        stocks.forEach(s -> found.add(s.getStockCode()));
        List<String> missing = codes.stream().filter(c -> !found.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new BizException(ApiCode.BAD_REQUEST, "股票不存在: " + String.join(",", missing));
        }
        if (!running.compareAndSet(false, true)) {
            return SyncAcceptVo.rejected("分红/财务同步正在执行，本次提交被跳过");
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
                "已提交 " + stocks.size() + " 只股票的分红/财务同步");
    }

    private String nextJobId() {
        return "dfs-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ==================== 执行 ====================

    private void runSync(List<Stock> stocks, String triggerType) {
        log.info("分红/财务同步开始：{} 只股票，触发方式 {}", stocks.size(), triggerType);
        DataSyncLog dividendLog = startLog(SYNC_TYPE_DIVIDEND, stocks.size());
        DataSyncLog financialLog = startLog(SYNC_TYPE_FINANCIAL, stocks.size());
        int dOk = 0, dFail = 0, fOk = 0, fFail = 0;
        for (int i = 0; i < stocks.size(); i++) {
            Stock stock = stocks.get(i);
            if (safeSync(stock, true)) {
                dOk++;
            } else {
                dFail++;
            }
            sleepBetweenRequests();
            if (safeSync(stock, false)) {
                fOk++;
            } else {
                fFail++;
            }
            if (i < stocks.size() - 1) {
                sleepBetweenRequests();
            }
        }
        finishLog(dividendLog, dOk, dFail);
        finishLog(financialLog, fOk, fFail);
        log.info("分红/财务同步结束：分红成功 {}/{}，财报成功 {}/{}", dOk, stocks.size(), fOk, stocks.size());
    }

    private boolean safeSync(Stock stock, boolean dividend) {
        try {
            return dividend ? syncStockDividends(stock) : syncStockFinancials(stock);
        } catch (Exception ex) {
            log.error("股票 {} {}同步异常: {}", stock.getStockCode(), dividend ? "分红" : "财报", ex.getMessage());
            return false;
        }
    }

    /**
     * 同步单只股票的分红方案。
     *
     * @return false 表示数据源取数失败（MISSING），true 表示取数成功（含 0 条记录）
     */
    boolean syncStockDividends(Stock stock) {
        List<DividendItem> items = dividendProvider.fetchDividends(stock.getStockCode());
        if (items == null) {
            return false;
        }
        List<StockDividend> existing = dividendMapper.selectList(Wrappers.<StockDividend>lambdaQuery()
                .eq(StockDividend::getStockId, stock.getId())
                .eq(StockDividend::getSourceCode, PROVIDER_CODE));
        int inserted = 0, updated = 0;
        for (DividendItem item : items) {
            StockDividend row = matchDividend(existing, item);
            if (row == null) {
                row = new StockDividend();
                applyDividend(row, item, stock.getId(), true);
                dividendMapper.insert(row);
                existing.add(row);
                inserted++;
            } else {
                if (applyDividend(row, item, stock.getId(), false)) {
                    dividendMapper.updateById(row);
                    updated++;
                }
            }
            syncShareEvents(stock.getId(), item);
        }
        log.debug("股票 {} 分红同步：{} 条（新增 {}，更新 {}）", stock.getStockCode(), items.size(), inserted, updated);
        return true;
    }

    /** 分红记录三级匹配：dividend_id → 年度+除息日 → 年度+公告日。 */
    private StockDividend matchDividend(List<StockDividend> existing, DividendItem item) {
        for (StockDividend row : existing) {
            if (item.dividendId() != null && item.dividendId().equals(row.getDividendId())) {
                return row;
            }
        }
        if (item.exDividendDate() != null) {
            for (StockDividend row : existing) {
                if (Objects.equals(item.fiscalYear(), row.getFiscalYear())
                        && item.exDividendDate().equals(row.getExDividendDate())) {
                    return row;
                }
            }
        }
        if (item.announcementDate() != null) {
            for (StockDividend row : existing) {
                if (Objects.equals(item.fiscalYear(), row.getFiscalYear())
                        && item.announcementDate().equals(row.getAnnouncementDate())) {
                    return row;
                }
            }
        }
        return null;
    }

    /** 应用分红字段；返回是否有变化（决定是否 update）。 */
    private boolean applyDividend(StockDividend row, DividendItem item, Long stockId, boolean insert) {
        if (insert) {
            row.setStockId(stockId);
            row.setCreatedAt(LocalDateTime.now());
        }
        boolean changed = insert
                || !Objects.equals(row.getDividendId(), item.dividendId())
                || !Objects.equals(row.getFiscalYear(), item.fiscalYear())
                || !sameDecimal(row.getCashDividendPerShare(), item.cashDividendPerShare())
                || !Objects.equals(row.getAnnouncementDate(), item.announcementDate())
                || !Objects.equals(row.getRecordDate(), item.recordDate())
                || (item.exDividendDate() != null
                        && !item.exDividendDate().equals(row.getExDividendDate()))
                || !Objects.equals(row.getPaymentDate(), item.paymentDate())
                || !Objects.equals(row.getPlanStatus(), item.planStatus())
                || !Objects.equals(row.getSourceCode(), PROVIDER_CODE);
        row.setDividendId(item.dividendId());
        row.setFiscalYear(item.fiscalYear());
        row.setCashDividendPerShare(item.cashDividendPerShare());
        row.setAnnouncementDate(item.announcementDate());
        row.setRecordDate(item.recordDate());
        // 除息日只在取到值时覆盖：预案阶段接口可能缺失，不把已有日期刷空
        if (item.exDividendDate() != null) {
            row.setExDividendDate(item.exDividendDate());
        }
        row.setPaymentDate(item.paymentDate());
        row.setPlanStatus(item.planStatus());
        row.setSourceCode(PROVIDER_CODE);
        // implementation_date 数据源不提供：仅插入时落 item 值（恒为 null），
        // 更新时绝不覆盖，保留人工/兜底已填写的实施日期
        if (insert) {
            row.setImplementationDate(item.implementationDate());
        }
        return changed;
    }

    /** 已实施且除息日存在的方案，按送转比例生成股本变动事件。 */
    private void syncShareEvents(Long stockId, DividendItem item) {
        if (item.exDividendDate() == null || !DividendStatusRules.isImplemented(item.planStatus())) {
            return;
        }
        upsertEvent(stockId, item.exDividendDate(), StockShareEvent.TYPE_BONUS, item.bonusRatio(), null);
        upsertEvent(stockId, item.exDividendDate(), StockShareEvent.TYPE_TRANSFER, null, item.transferRatio());
    }

    private void upsertEvent(Long stockId, LocalDate eventDate, String type,
                             BigDecimal bonusRatio, BigDecimal transferRatio) {
        BigDecimal ratio = StockShareEvent.TYPE_BONUS.equals(type) ? bonusRatio : transferRatio;
        if (ratio == null || ratio.signum() <= 0) {
            return;
        }
        StockShareEvent existing = shareEventMapper.selectOne(Wrappers.<StockShareEvent>lambdaQuery()
                .eq(StockShareEvent::getStockId, stockId)
                .eq(StockShareEvent::getEventDate, eventDate)
                .eq(StockShareEvent::getEventType, type)
                .eq(StockShareEvent::getSourceCode, PROVIDER_CODE));
        if (existing == null) {
            StockShareEvent event = new StockShareEvent();
            event.setStockId(stockId);
            event.setEventDate(eventDate);
            event.setEventType(type);
            event.setBonusRatio(bonusRatio);
            event.setTransferRatio(transferRatio);
            event.setSourceCode(PROVIDER_CODE);
            event.setCreatedAt(LocalDateTime.now());
            shareEventMapper.insert(event);
        } else if (!sameDecimal(existing.getBonusRatio(), bonusRatio)
                || !sameDecimal(existing.getTransferRatio(), transferRatio)) {
            existing.setBonusRatio(bonusRatio);
            existing.setTransferRatio(transferRatio);
            shareEventMapper.updateById(existing);
        }
    }

    /**
     * 同步单只股票的财报。
     *
     * @return false 表示数据源取数失败（MISSING），true 表示取数成功（含 0 条记录）
     */
    boolean syncStockFinancials(Stock stock) {
        List<FinancialItem> items = financialProvider.fetchFinancials(stock.getStockCode());
        if (items == null) {
            return false;
        }
        List<StockFinancial> existing = financialMapper.selectList(Wrappers.<StockFinancial>lambdaQuery()
                .eq(StockFinancial::getStockId, stock.getId())
                .eq(StockFinancial::getSourceCode, PROVIDER_CODE));
        Map<String, StockFinancial> byPeriod = new LinkedHashMap<>();
        existing.forEach(f -> byPeriod.put(f.getReportPeriod(), f));
        int inserted = 0, updated = 0;
        for (FinancialItem item : items) {
            StockFinancial row = byPeriod.get(item.reportPeriod());
            boolean isNew = row == null;
            if (isNew) {
                row = new StockFinancial();
                row.setStockId(stock.getId());
                row.setReportPeriod(item.reportPeriod());
                row.setSourceCode(PROVIDER_CODE);
            }
            if (isNew || applyFinancial(row, item)) {
                if (isNew) {
                    financialMapper.insert(row);
                    byPeriod.put(item.reportPeriod(), row);
                    inserted++;
                } else {
                    financialMapper.updateById(row);
                    updated++;
                }
            }
        }
        log.debug("股票 {} 财报同步：{} 条（新增 {}，更新 {}）", stock.getStockCode(), items.size(), inserted, updated);
        return true;
    }

    /** 应用财报字段；返回是否有变化。数据源不提供的字段（净利润总额、同比）一律不动。 */
    private boolean applyFinancial(StockFinancial row, FinancialItem item) {
        boolean changed = !Objects.equals(row.getReportType(), item.reportType())
                || !sameDecimal(row.getRevenue(), item.revenue())
                || !sameDecimal(row.getNetProfitParent(), item.netProfitParent())
                || !sameDecimal(row.getEps(), item.eps())
                || !sameDecimal(row.getRoe(), item.roe())
                || !Objects.equals(row.getPublishDate(), item.publishDate());
        row.setReportType(item.reportType());
        row.setRevenue(item.revenue());
        row.setNetProfitParent(item.netProfitParent());
        row.setEps(item.eps());
        row.setRoe(item.roe());
        row.setPublishDate(item.publishDate());
        row.setFetchTime(LocalDateTime.now());
        return changed;
    }

    // ==================== 自选股与日志 ====================

    private List<String> watchlistStockCodes() {
        List<UserWatchlist> items = watchlistMapper.selectList(
                Wrappers.<UserWatchlist>lambdaQuery().select(UserWatchlist::getStockId));
        if (items.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new HashSet<>();
        items.forEach(i -> ids.add(i.getStockId()));
        return stockMapper.selectBatchIds(ids).stream()
                .map(Stock::getStockCode).sorted().toList();
    }

    private DataSyncLog startLog(String syncType, int total) {
        DataSyncLog entry = new DataSyncLog();
        entry.setProviderCode(PROVIDER_CODE);
        entry.setSyncType(syncType);
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
        entry.setErrorMessage(failed > 0 ? failed + " 只股票同步失败（接口失败或异常，见应用日志）" : null);
        syncLogMapper.updateById(entry);
    }

    private void sleepBetweenRequests() {
        if (properties.getRequestIntervalMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(properties.getRequestIntervalMs());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** BigDecimal 数值相等（忽略标度，避免 10.0 vs 10.00 误判为变化）。 */
    private static boolean sameDecimal(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return a.compareTo(b) == 0;
    }
}
