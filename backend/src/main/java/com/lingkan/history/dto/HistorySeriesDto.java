package com.lingkan.history.dto;

import java.util.List;

/**
 * 历史序列。
 */
public record HistorySeriesDto(
        String stockCode,
        String metricCode,
        String metricName,
        String unit,
        String range,
        String sourceCode,
        List<Point> points
) {

    public record Point(String date, java.math.BigDecimal value) {
    }
}
