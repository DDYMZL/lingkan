package com.lingkan.sync.scheduler;

import com.lingkan.sync.DividendFinancialSyncProperties;
import com.lingkan.sync.dto.SyncAcceptVo;
import com.lingkan.sync.service.DividendFinancialSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 分红/财务每日同步调度器（spec 010 §4.3）。
 *
 * <p>每天 05:30（北京时间）同步全部自选股的分红方案与财报；
 * 只通过 Service 提交任务，不直接执行采集逻辑；
 * 开关关闭后定时任务不执行，手动接口与添加自选触发仍可用。
 */
@Component
public class DividendFinancialSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DividendFinancialSyncScheduler.class);

    private final DividendFinancialSyncService syncService;
    private final DividendFinancialSyncProperties properties;

    public DividendFinancialSyncScheduler(DividendFinancialSyncService syncService,
                                          DividendFinancialSyncProperties properties) {
        this.syncService = syncService;
        this.properties = properties;
    }

    /** 每天凌晨 05:30 自选股分红/财务增量同步 */
    @Scheduled(cron = "${lingkan.dividend-financial-sync.cron:0 30 5 * * ?}",
            zone = "${lingkan.dividend-financial-sync.timezone:Asia/Shanghai}")
    public void dailySync() {
        if (!properties.isEnabled()) {
            log.debug("分红/财务同步已关闭（lingkan.dividend-financial-sync.enabled=false），跳过本次定时任务");
            return;
        }
        try {
            SyncAcceptVo vo = syncService.submitForWatchlist("SCHEDULED");
            log.info("定时分红/财务同步提交：{}", vo.message());
        } catch (Exception ex) {
            // 定时任务不能抛出异常，否则调度线程会吞掉后续触发
            log.error("定时分红/财务同步提交失败: {}", ex.getMessage());
        }
    }
}
