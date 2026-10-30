package com.lingkan.sync;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 分红/财务同步配置（前缀 {@code lingkan.dividend-financial-sync}）。
 *
 * <p>与 {@code lingkan.stock-sync}（全市场股票清单/行情/指标同步）互相独立：
 * 本同步只覆盖自选股的分红方案与财报，数据量小、频率低（每日一次 + 事件触发）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lingkan.dividend-financial-sync")
public class DividendFinancialSyncProperties {

    /** 定时同步开关；关闭后定时任务不执行，手动接口与添加自选触发仍可用 */
    private boolean enabled = true;

    /** 每日同步 cron（默认每天 05:30） */
    private String cron = "0 30 5 * * ?";

    /** 定时任务时区，固定北京时间，不受服务器时区影响 */
    private String timezone = "Asia/Shanghai";

    /** 相邻两次数据源请求的最小间隔（毫秒）；每股分红与财报各一次请求 */
    private int requestIntervalMs = 500;
}
