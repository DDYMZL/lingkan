package com.lingkan.sync;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步配置（前缀 {@code lingkan.stock-sync}）。
 *
 * <p>为什么单独用 {@code lingkan.stock-sync} 而不是 {@code lingkan.sync}：后者的
 * {@code enabled} 已被现有分时行情采集调度器（{@code QuoteSyncScheduler}）占用，
 * 两者语义不同（一个是分钟级行情轮询开关，一个是全市场日同步开关），
 * 复用同一开关会导致无法独立控制。
 *
 * <p>只新增本功能自己的配置项，不覆盖原有数据源配置；参数默认值均可在 application.yml 调整。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lingkan.stock-sync")
public class StockSyncProperties {

    /** 同步功能总开关；关闭后定时任务不执行，手动接口仍可用（便于排障） */
    private boolean enabled = false;

    /** 每日增量同步 cron（默认每天 04:00） */
    private String cron = "0 0 4 * * ?";

    /** 定时任务时区，固定北京时间，不受服务器时区影响 */
    private String timezone = "Asia/Shanghai";

    /** 股票基础信息批量入库条数 */
    private int batchSize = 500;

    /** 是否在启动时自动执行全量同步；默认关闭（禁止重启即全量） */
    private boolean autoInitialize = false;

    private StockList stockList = new StockList();
    private Quote quote = new Quote();
    private Eastmoney eastmoney = new Eastmoney();
    private Retry retry = new Retry();
    private Task task = new Task();

    @Data
    public static class StockList {
        /** 主数据源：eastmoney（东方财富），失败时回退 fallback */
        private String provider = "eastmoney";
        /** 备用数据源，空字符串表示不启用回退 */
        private String fallback = "sina";
        private int pageSize = 100;
        private int maxPages = 120;
        private int requestIntervalMs = 300;
        private int maxRetries = 3;
        private int retryIntervalMs = 2000;
        /** 有效股票数下限，低于该值判定清单异常 */
        private int minStockCount = 3000;
        private List<String> hosts = new ArrayList<>(List.of(
                "push2.eastmoney.com", "82.push2.eastmoney.com", "push2delay.eastmoney.com"));
    }

    @Data
    public static class Quote {
        private boolean enabled = true;
        /** 行情数据源编码，必须是已实现的 QuoteProvider（当前为 tencent） */
        private String provider = "tencent";
        /** 单次行情请求携带的股票数 */
        private int batchSize = 100;
        private int maxRetries = 3;
        private int retryIntervalMs = 1000;
    }

    @Data
    public static class Eastmoney {
        private boolean enabled = true;
        /** 有界并发线程数 */
        private int concurrency = 6;
        /** 每个并发线程两次请求之间的最小间隔 */
        private int requestIntervalMs = 150;
        private int maxRetries = 2;
        private int retryIntervalMs = 800;
    }

    @Data
    public static class Retry {
        private int maxAttempts = 3;
        private int initialDelayMs = 1000;
        private int multiplier = 2;
    }

    @Data
    public static class Task {
        /** 任务锁超时时间（分钟），超时后允许新任务抢占 */
        private int lockTimeoutMinutes = 120;
        /** 是否记录失败明细（关闭后只统计数量，不写入明细表） */
        private boolean detailLogEnabled = true;
    }
}
