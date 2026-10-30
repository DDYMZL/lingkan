package com.lingkan.financial.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 财务数据。report_period 形如 2026Q2 / 2026H1 / 2026A。
 */
@Data
@TableName("stock_financials")
public class StockFinancial {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stockId;
    private String reportPeriod;
    /** Q1 / H1 / Q3 / ANNUAL */
    private String reportType;
    private BigDecimal revenue;
    private BigDecimal netProfit;
    private BigDecimal netProfitParent;
    private BigDecimal revenueYoy;
    private BigDecimal netProfitYoy;
    private BigDecimal netProfitParentYoy;
    private BigDecimal nonRecurringProfitYoy;
    private BigDecimal eps;
    private BigDecimal roe;
    private LocalDate publishDate;
    private String sourceCode;
    private LocalDateTime fetchTime;
}
