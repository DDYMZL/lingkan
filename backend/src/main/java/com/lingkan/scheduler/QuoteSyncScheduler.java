package com.lingkan.scheduler;

import com.lingkan.metrics.service.QuoteSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 行情采集调度器。
 *
 * <p>当前默认关闭（lingkan.sync.enabled=false）：数据源方案尚未经用户确认前不主动采集，
 * 避免对第三方免费接口造成压力，也避免污染数据库。
 * 开启后按配置频率刷新自选股行情。
 */
@Component
public class QuoteSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuoteSyncScheduler.class);

    private final QuoteSnapshotService quoteService;

    @Value("${lingkan.sync.enabled:false}")
    private boolean syncEnabled;

    public QuoteSyncScheduler(QuoteSnapshotService quoteService) {
        this.quoteService = quoteService;
    }

    @Scheduled(fixedDelayString = "${lingkan.sync.quote-interval-ms:60000}",
            initialDelayString = "${lingkan.sync.initial-delay-ms:30000}")
    public void syncQuotes() {
        if (!syncEnabled) {
            log.debug("行情采集已关闭（lingkan.sync.enabled=false），跳过本次调度");
            return;
        }
        log.info("行情采集任务开始（当前为骨架实现，自选股采集将在 Phase 4 接入）");
    }
}
