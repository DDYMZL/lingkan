package com.lingkan.stock.dto;

import com.lingkan.metrics.dto.MetricValueDto;

import java.util.Map;

/**
 * 股票列表行：股票基础信息 + 按用户配置动态返回的指标值。
 * 列表字段完全由指标配置决定，没有写死的列。
 */
public record StockRowDto(
        String stockCode,
        String stockName,
        String exchange,
        String industry,
        boolean st,
        Map<String, MetricValueDto> metrics
) {
}
