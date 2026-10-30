package com.lingkan.stock.dto;

import com.lingkan.metrics.dto.MetricValueDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 股票详情：基础信息 + 行情 + 动态指标。
 */
public record StockDetailDto(
        StockBasic stock,
        Quote quote,
        List<MetricValueDto> metrics
) {

    public record StockBasic(
            String stockCode,
            String stockName,
            String exchange,
            String market,
            String board,
            String industry,
            String listingDate,
            boolean st
    ) {
    }

    public record Quote(
            String stockCode,
            BigDecimal latestPrice,
            BigDecimal changePercent,
            BigDecimal changeAmount,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal preClose,
            BigDecimal volume,
            BigDecimal amount,
            BigDecimal turnoverRate,
            BigDecimal totalMarketCap,
            String sourceCode,
            String sourceName,
            LocalDateTime dataTime,
            LocalDateTime fetchTime
    ) {
    }
}
