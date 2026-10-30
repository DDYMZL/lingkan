package com.lingkan.metrics.dto;

import java.time.LocalDateTime;

/**
 * 指标取值（含来源与时间）。这是小程序端所有指标展示的统一载体。
 *
 * @param value        归一化值，可为数值/布尔/文本；无有效数据时为 null
 * @param displayValue 已按精度与单位格式化的展示文本
 * @param status       OK / MISSING / STALE / ERROR / PENDING / NOT_APPLICABLE
 */
public record MetricValueDto(
        String metricCode,
        String metricName,
        String unit,
        int precision,
        Object value,
        String displayValue,
        String status,
        String statusText,
        String sourceCode,
        String sourceName,
        LocalDateTime dataTime,
        LocalDateTime fetchTime,
        String calculationVersion
) {

    public static MetricValueDto pending(String metricCode, String metricName, String unit,
                                         String reason) {
        return new MetricValueDto(metricCode, metricName, unit, 0, null, "--", "PENDING",
                reason, null, null, null, null, null);
    }

    public static MetricValueDto missing(String metricCode, String metricName, String unit,
                                         String precision, String reason) {
        return new MetricValueDto(metricCode, metricName, unit,
                precision == null ? 2 : Integer.parseInt(precision), null, "--", "MISSING",
                reason, null, null, null, null, null);
    }
}
