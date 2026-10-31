package com.lingkan.datasource.provider.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 周线 K 线单根（数据源统一 DTO，与库表结构解耦）。
 *
 * <p>{@code tradeDate} 为该周最后一个交易日（东财周 K 接口返回口径）；
 * 价格复权方式由适配器决定，看板 v1.2 固定取前复权（spec 010 §3.5）。
 * 成交量单位为手、成交额单位为元，与日线口径一致。
 */
public record WeeklyBar(
        LocalDate tradeDate,
        BigDecimal open,
        BigDecimal close,
        BigDecimal high,
        BigDecimal low,
        BigDecimal volume,
        BigDecimal amount
) {
}
