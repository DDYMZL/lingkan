package com.lingkan.metrics.dto;

import java.util.List;

/**
 * 单指标多来源对比结果。
 */
public record MetricSourceComparisonDto(
        String stockCode,
        String stockName,
        String metricCode,
        String metricName,
        String unit,
        String selectedSource,
        List<MetricSourceValueDto> sources
) {
}
