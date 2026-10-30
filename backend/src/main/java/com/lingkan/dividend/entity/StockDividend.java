package com.lingkan.dividend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 分红记录。
 *
 * <p>注意：cash_dividend_per_share 为每股现金分红（税前），送股/转增不计入现金股息。
 * plan_status 用于区分"预案/已实施/未实施"，股息率 TTM 只统计已实施部分。
 */
@Data
@TableName("stock_dividends")
public class StockDividend {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stockId;
    private String fiscalYear;
    /** 分红方案唯一标识（数据源口径），用于去重 */
    private String dividendId;
    private BigDecimal cashDividendPerShare;
    private LocalDate announcementDate;
    private LocalDate recordDate;
    private LocalDate exDividendDate;
    private LocalDate paymentDate;
    /** 实际派息实施日期（TTM 窗口优先按此日期筛选，缺失时用除息日兜底） */
    private LocalDate implementationDate;
    private String planStatus;
    private String sourceCode;
    private LocalDateTime createdAt;
}
