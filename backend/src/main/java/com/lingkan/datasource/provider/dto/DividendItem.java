package com.lingkan.datasource.provider.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 分红送配记录（数据源统一 DTO，与库表结构解耦）。
 *
 * <p>金额口径：每股现金分红（税前）；送转比例为「每 10 股送/转 X 股 → X/10」。
 */
public record DividendItem(
        String stockCode,
        /** 分红方案唯一标识（由适配器按数据源口径构造，用于去重） */
        String dividendId,
        /** 分红归属年度，如 2025 */
        String fiscalYear,
        /** 每股现金分红（税前，元）；无现金分红为 null */
        BigDecimal cashDividendPerShare,
        /** 预案公告日期 */
        LocalDate announcementDate,
        /** 股权登记日 */
        LocalDate recordDate,
        /** 除权除息日 */
        LocalDate exDividendDate,
        /** 实际派息日；数据源无此字段时为 null */
        LocalDate paymentDate,
        /** 实施日期；数据源无此字段时为 null（TTM 窗口按除息日兜底） */
        LocalDate implementationDate,
        /** 方案进度原文（如「实施分配」「预案」） */
        String planStatus,
        /** 每 10 股送股数 ÷ 10；无送股为 null */
        BigDecimal bonusRatio,
        /** 每 10 股转增数 ÷ 10；无转增为 null */
        BigDecimal transferRatio,
        /** 方案原文描述（追溯用，如「10转4股派10.00元(含税)」） */
        String planProfile
) {
}
