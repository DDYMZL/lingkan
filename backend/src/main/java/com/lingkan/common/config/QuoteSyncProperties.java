package com.lingkan.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 自选股行情轮询、共享缓存与 WebSocket 推送配置。 */
@Component
@ConfigurationProperties(prefix = "lingkan.sync")
public class QuoteSyncProperties {

    /** 行情轮询总开关，默认关闭，避免未部署确认前主动采集。 */
    private boolean enabled = false;
    /** 仅工作日交易时段执行上游轮询。 */
    private boolean marketHoursOnly = true;
    /** 单次上游批量请求股票数。 */
    private int quoteBatchSize = 100;
    /** 按股票代码共享的 Caffeine 新鲜缓存 TTL。 */
    private long quoteCacheTtlSeconds = 60;
    /** 行情采集时间超过该阈值且无法刷新时标记 STALE。 */
    private long quoteStaleAfterSeconds = 90;
    /** 是否向已连接会话推送价格变化。 */
    private boolean websocketEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMarketHoursOnly() {
        return marketHoursOnly;
    }

    public void setMarketHoursOnly(boolean marketHoursOnly) {
        this.marketHoursOnly = marketHoursOnly;
    }

    public int getQuoteBatchSize() {
        return quoteBatchSize;
    }

    public void setQuoteBatchSize(int quoteBatchSize) {
        this.quoteBatchSize = quoteBatchSize;
    }

    public long getQuoteCacheTtlSeconds() {
        return quoteCacheTtlSeconds;
    }

    public void setQuoteCacheTtlSeconds(long quoteCacheTtlSeconds) {
        this.quoteCacheTtlSeconds = quoteCacheTtlSeconds;
    }

    public long getQuoteStaleAfterSeconds() {
        return quoteStaleAfterSeconds;
    }

    public void setQuoteStaleAfterSeconds(long quoteStaleAfterSeconds) {
        this.quoteStaleAfterSeconds = quoteStaleAfterSeconds;
    }

    public boolean isWebsocketEnabled() {
        return websocketEnabled;
    }

    public void setWebsocketEnabled(boolean websocketEnabled) {
        this.websocketEnabled = websocketEnabled;
    }

    public int batchSizeOrDefault() {
        return quoteBatchSize > 0 ? quoteBatchSize : 100;
    }

    public long cacheTtlOrDefault() {
        return quoteCacheTtlSeconds > 0 ? quoteCacheTtlSeconds : 60;
    }

    public long staleAfterOrDefault() {
        return quoteStaleAfterSeconds > 0 ? quoteStaleAfterSeconds : 90;
    }
}
