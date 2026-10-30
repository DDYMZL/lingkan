package com.lingkan.metrics.dto;

/**
 * 多数据源对比项。
 */
public record MetricSourceValueDto(
        String sourceCode,
        String sourceName,
        Object value,
        String displayValue,
        String unit,
        java.time.LocalDateTime dataTime,
        java.time.LocalDateTime fetchTime,
        String status,
        java.math.BigDecimal diffFromPrimary,
        boolean primary
) {
}
