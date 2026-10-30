package com.lingkan.sync.scheduler;

import com.lingkan.sync.StockSyncProperties;
import com.lingkan.sync.service.StockSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日全市场同步调度器。
 *
 * <p>约束：
 * <ol>
 *   <li>固定北京时间（{@code zone = Asia/Shanghai}），不受服务器操作系统时区影响；</li>
 *   <li>cron 与开关均可配，关闭后定时任务不执行，但手动接口仍可用；</li>
 *   <li>不随服务重启自动执行全量：{@code auto-initialize} 默认 false，
 *       避免每次重启都触发 5000 只股票的上游请求；</li>
 *   <li>只通过 Service 提交任务，不直接调用执行器或 Controller。</li>
 * </ol>
 */
@Component
public class StockSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(StockSyncScheduler.class);

    private final StockSyncService syncService;
    private final StockSyncProperties properties;

    public StockSyncScheduler(StockSyncService syncService, StockSyncProperties properties) {
        this.syncService = syncService;
        this.properties = properties;
    }

    /** 每天凌晨 04:00 增量同步（清单更新 + 全部行情 + 全部绑定指标） */
    @Scheduled(cron = "${lingkan.stock-sync.cron:0 0 4 * * ?}",
            zone = "${lingkan.stock-sync.timezone:Asia/Shanghai}")
    public void dailySync() {
        if (!properties.isEnabled()) {
            log.debug("全市场同步已关闭（lingkan.stock-sync.enabled=false），跳过本次定时任务");
            return;
        }
        try {
            log.info("定时增量同步开始");
            syncService.submitIncremental("SCHEDULED");
        } catch (Exception ex) {
            // 定时任务不能抛出异常，否则调度线程会吞掉后续触发
            log.error("定时增量同步提交失败: {}", ex.getMessage());
        }
    }

    /**
     * 可选的启动自动初始化（默认关闭）。
     *
     * <p>仅当显式配置 {@code lingkan.stock-sync.auto-initialize=true} 且库中尚无股票时才执行，
     * 双重条件避免"重启即全量"。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoInitialize() {
        if (!properties.isEnabled() || !properties.isAutoInitialize()) {
            return;
        }
        try {
            log.info("auto-initialize=true，提交全量初始化任务");
            syncService.submitFull("SCHEDULED");
        } catch (Exception ex) {
            log.warn("启动自动全量同步未执行: {}", ex.getMessage());
        }
    }
}
