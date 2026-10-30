package com.lingkan.metrics.dto;

/**
 * 指标定义展示对象。
 */
public record MetricDefinitionDto(
        String metricCode,
        String metricName,
        String category,
        String dataType,
        String unit,
        boolean enabled,
        String calculationType,
        String calculationStatus,
        int defaultPrecision,
        String description,
        String calculationExpression,
        String pendingQuestions,
        int sortOrder
) {
}
