package com.lingkan.datasource.provider;

import java.util.List;

/**
 * 数据源统一接口。每种数据源实现一个适配器，Controller 不得直接调用第三方接口。
 */
public interface StockDataProvider {

    /** 数据源编码，与 data_providers.provider_code 一致 */
    String getProviderCode();

    String getProviderName();

    /** 该数据源是否支持某个指标 */
    boolean supports(String metricCode);

    /** 单指标取数 */
    ProviderResult fetch(String stockCode, String metricCode);

    /** 连通性检查（真实发起一次请求） */
    ProviderHealth checkHealth();

    /** 声明支持的指标编码；返回空列表表示当前不可用于取数 */
    default List<String> supportedMetrics() {
        return List.of();
    }
}
