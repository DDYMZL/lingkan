package com.lingkan.datasource.provider;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 批量行情结果。字段可为 null，表示该数据源未提供此项，禁止用 0 填充。
 */
public record QuoteResult(
        String stockCode,
        String stockName,
        String sourceCode,
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
        /** 振幅（%），数据源未提供时为 null */
        BigDecimal amplitude,
        /** 流通市值（元），数据源未提供时为 null */
        BigDecimal circulatingMarketCap,
        /** 市净率（倍），数据源未提供时为 null */
        BigDecimal pb,
        LocalDateTime dataTime,
        LocalDateTime fetchTime
) {
}
