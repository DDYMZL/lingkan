package com.lingkan.datasource.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量指标取数能力（可选扩展）。
 *
 * <p>存在原因：东方财富的 {@code stock/get} 接口一次请求即返回一只股票的全部快照字段。
 * 若按"单股单指标"调用，一只股票 14 项指标 = 14 次 HTTP 请求，全市场 5000 只股票
 * 将产生 7 万次请求，既慢又容易被上游限流。
 *
 * <p>因此保留 {@link StockDataProvider#fetch(String, String)} 作为通用契约，
 * 另设本接口供"一次请求可覆盖多指标"的数据源实现；同步编排层对实现了本接口的数据源
 * 优先走批量路径，其余数据源仍走单指标循环，行为完全一致。
 */
public interface BatchStockDataProvider extends StockDataProvider {

    /**
     * 一次请求取回多指标。
     *
     * @param stockCode   股票代码
     * @param metricCodes 需要取数的指标编码
     * @return 指标编码 → 取数结果；无法取到的指标必须返回 MISSING 状态而不是缺失 key
     */
    Map<String, ProviderResult> fetchMetrics(String stockCode, List<String> metricCodes);

    /** 默认实现：逐指标回退到单指标接口，保证语义一致 */
    default Map<String, ProviderResult> fetchMetricsFallback(String stockCode, List<String> metricCodes) {
        Map<String, ProviderResult> result = new LinkedHashMap<>();
        for (String metricCode : metricCodes) {
            result.put(metricCode, fetch(stockCode, metricCode));
        }
        return result;
    }
}
