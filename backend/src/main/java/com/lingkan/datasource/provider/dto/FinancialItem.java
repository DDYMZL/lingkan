package com.lingkan.datasource.provider.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 财务报告记录（数据源统一 DTO）。
 *
 * <p>注意：东方财富返回的营收/归母净利润为<b>报告期累计值</b>（三季报 = 前三季度之和），
 * 计算 TTM 时必须先转换为单季值（见 PeTtmCalculator），禁止直接累加累计值。
 */
public record FinancialItem(
        String stockCode,
        /** 报告期，如 2026Q3 / 2025Q4（年报用 Q4 表示） */
        String reportPeriod,
        /** Q1 / H1 / Q3 / ANNUAL */
        String reportType,
        /** 报告期截止日，如 2026-09-30 */
        LocalDate reportDate,
        /** 营业收入（累计，元） */
        BigDecimal revenue,
        /** 归母净利润（累计，元） */
        BigDecimal netProfitParent,
        /** 基本每股收益（元） */
        BigDecimal eps,
        /** 加权平均净资产收益率（%） */
        BigDecimal roe,
        /** 披露日期 */
        LocalDate publishDate
) {
}
