package com.lingkan.sync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingkan.common.config.AsyncConfig;
import com.lingkan.common.exception.BizException;
import com.lingkan.common.response.ApiCode;
import com.lingkan.common.response.PageResult;
import com.lingkan.sync.StockSyncProperties;
import com.lingkan.sync.dto.StockSyncDetailVo;
import com.lingkan.sync.dto.StockSyncTaskVo;
import com.lingkan.sync.dto.SyncSubmitVo;
import com.lingkan.sync.entity.StockSyncTask;
import com.lingkan.sync.entity.StockSyncTaskDetail;
import com.lingkan.sync.mapper.StockSyncTaskDetailMapper;
import com.lingkan.sync.mapper.StockSyncTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 同步任务应用服务：负责"创建任务 → 抢锁 → 异步执行 → 对外查询"。
 *
 * <p>职责边界：只做任务管理与编排入口，具体执行细节在 {@link StockSyncExecutor}；
 * 接口层不直接调用执行器，也不允许定时任务直接调用 Controller。
 *
 * <p>互斥策略：任务锁在提交阶段同步获取，获取失败立即拒绝（而不是排队），
 * 这样 FULL 与 FULL、FULL 与定时增量、手动与定时之间天然互斥。
 */
@Service
public class StockSyncService {

    private static final Logger log = LoggerFactory.getLogger(StockSyncService.class);

    private static final DateTimeFormatter TASK_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StockSyncExecutor executor;
    private final StockSyncLockService lockService;
    private final StockSyncTaskMapper taskMapper;
    private final StockSyncTaskDetailMapper detailMapper;
    private final StockSyncProperties properties;
    private final Executor dataSyncExecutor;

    public StockSyncService(StockSyncExecutor executor, StockSyncLockService lockService,
                            StockSyncTaskMapper taskMapper, StockSyncTaskDetailMapper detailMapper,
                            StockSyncProperties properties,
                            @Qualifier(AsyncConfig.DATA_SYNC_EXECUTOR) Executor dataSyncExecutor) {
        this.executor = executor;
        this.lockService = lockService;
        this.taskMapper = taskMapper;
        this.detailMapper = detailMapper;
        this.properties = properties;
        this.dataSyncExecutor = dataSyncExecutor;
    }

    // ==================== 提交 ====================

    public SyncSubmitVo submitFull(String triggerType) {
        return submit(StockSyncExecutor.TYPE_FULL, triggerType, StockSyncExecutor.SyncScope.none());
    }

    public SyncSubmitVo submitIncremental(String triggerType) {
        return submit(StockSyncExecutor.TYPE_INCREMENTAL, triggerType, StockSyncExecutor.SyncScope.none());
    }

    public SyncSubmitVo submitSingle(List<String> stockCodes) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            throw new BizException(ApiCode.BAD_REQUEST, "stockCodes 不能为空");
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String code : stockCodes) {
            if (code != null && !code.isBlank()) {
                codes.add(code.trim());
            }
        }
        if (codes.isEmpty()) {
            throw new BizException(ApiCode.BAD_REQUEST, "stockCodes 不能为空");
        }
        return submit(StockSyncExecutor.TYPE_SINGLE, "MANUAL",
                StockSyncExecutor.SyncScope.ofStocks(new ArrayList<>(codes)));
    }

    /**
     * 重试指定任务的失败项。
     *
     * <p>只处理源任务明细中 status=FAILED 的股票与指标，不重复执行已成功的数据。
     */
    public SyncSubmitVo retry(Long originTaskId) {
        StockSyncTask origin = taskMapper.selectById(originTaskId);
        if (origin == null) {
            throw new BizException(ApiCode.BAD_REQUEST, "任务不存在: " + originTaskId);
        }
        Long failed = detailMapper.selectCount(Wrappers.<StockSyncTaskDetail>lambdaQuery()
                .eq(StockSyncTaskDetail::getTaskId, originTaskId)
                .eq(StockSyncTaskDetail::getStatus, "FAILED"));
        if (failed == null || failed == 0) {
            throw new BizException(ApiCode.BAD_REQUEST, "该任务没有失败记录，无需重试");
        }
        return submit(StockSyncExecutor.TYPE_RETRY, "MANUAL",
                StockSyncExecutor.SyncScope.retryOf(originTaskId));
    }

    private SyncSubmitVo submit(String syncType, String triggerType, StockSyncExecutor.SyncScope scope) {
        if (!properties.isEnabled()) {
            // 定时任务关闭时仍允许手动执行，便于排障；但要在返回里说明
            log.info("全市场同步开关未开启（lingkan.stock-sync.enabled=false），本次为手动执行");
        }
        String taskNo = nextTaskNo();
        if (!lockService.tryAcquire(taskNo, syncType + " 同步任务")) {
            throw new BizException(ApiCode.BAD_REQUEST,
                    "已有同步任务正在执行（" + lockService.describe() + "），请等待其完成");
        }
        StockSyncTask task = new StockSyncTask();
        task.setTaskNo(taskNo);
        task.setSyncType(syncType);
        task.setStatus(StockSyncExecutor.STATUS_PENDING);
        task.setTriggerType(triggerType == null ? "MANUAL" : triggerType);
        task.setProgress(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setTotalStocks(0);
        task.setSuccessStocks(0);
        task.setFailedStocks(0);
        task.setStockAdded(0);
        task.setStockUpdated(0);
        task.setStockSkipped(0);
        task.setQuoteTotal(0);
        task.setQuoteSuccess(0);
        task.setQuoteFailed(0);
        task.setMetricTotal(0);
        task.setMetricSuccess(0);
        task.setMetricFailed(0);
        try {
            taskMapper.insert(task);
        } catch (RuntimeException ex) {
            // 建任务失败必须立刻释放锁，否则后续任务全部被拒
            lockService.release(taskNo);
            throw ex;
        }
        log.info("同步任务已创建：id={} taskNo={} type={}", task.getId(), taskNo, syncType);
        dataSyncExecutor.execute(() -> executor.execute(task, scope));
        return SyncSubmitVo.of(task, "同步任务已创建");
    }

    /** 任务编号：SYNC + yyyyMMdd + 4 位日内序号 */
    private String nextTaskNo() {
        String prefix = "SYNC" + LocalDateTime.now().format(TASK_NO_DATE);
        int seq = taskMapper.nextSequence(prefix);
        return prefix + String.format("%04d", Math.max(1, seq));
    }

    // ==================== 查询 ====================

    public StockSyncTaskVo detail(Long taskId) {
        StockSyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ApiCode.BAD_REQUEST, "任务不存在: " + taskId);
        }
        return StockSyncTaskVo.of(task);
    }

    public PageResult<StockSyncTaskVo> list(int page, int size) {
        Page<StockSyncTask> paged = taskMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 200)),
                Wrappers.<StockSyncTask>lambdaQuery().orderByDesc(StockSyncTask::getId));
        List<StockSyncTaskVo> list = new ArrayList<>(paged.getRecords().size());
        paged.getRecords().forEach(t -> list.add(StockSyncTaskVo.of(t)));
        return new PageResult<>(list, paged.getTotal(), page, size);
    }

    public PageResult<StockSyncDetailVo> failures(Long taskId, int page, int size) {
        Page<StockSyncTaskDetail> paged = detailMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, size), 200)),
                Wrappers.<StockSyncTaskDetail>lambdaQuery()
                        .eq(StockSyncTaskDetail::getTaskId, taskId)
                        .eq(StockSyncTaskDetail::getStatus, "FAILED")
                        .orderByAsc(StockSyncTaskDetail::getId));
        List<StockSyncDetailVo> list = new ArrayList<>(paged.getRecords().size());
        paged.getRecords().forEach(d -> list.add(StockSyncDetailVo.of(d)));
        return new PageResult<>(list, paged.getTotal(), page, size);
    }

    // ==================== 启动清理 ====================

    /**
     * 启动时把上次进程遗留的 PENDING/RUNNING 任务标记为失败，并释放其任务锁。
     *
     * <p>只更新任务状态、释放锁，不删除也不回滚任何已入库数据（符合"断点保留已成功数据"）。
     * 本项目为单机部署：新进程启动时内存中没有任何在跑的任务，因此所有执行中态都是遗留记录；
     * 若将来改为多实例部署，这里必须改成"仅清理本实例标识"的语义。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanStaleTasks() {
        try {
            List<StockSyncTask> active = taskMapper.selectActiveTasks();
            int marked = taskMapper.markActiveFailed(
                    "服务重启导致任务中断（已入库数据保留，可发起新任务或重试失败项）");
            for (StockSyncTask task : active) {
                lockService.release(task.getTaskNo());
            }
            if (marked > 0) {
                log.warn("已把 {} 个遗留同步任务标记为失败，并释放对应任务锁", marked);
            }
        } catch (Exception ex) {
            log.warn("启动清理遗留任务失败: {}", ex.getMessage());
        }
    }
}
