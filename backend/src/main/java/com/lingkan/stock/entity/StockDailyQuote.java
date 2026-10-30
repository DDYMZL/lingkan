package com.lingkan.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 历史日线。adjustment_type: NONE / QFQ / HFQ
 */
@Data
@TableName("stock_daily_quotes")
public class StockDailyQuote {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stockId;
    private LocalDate tradeDate;
    private BigDecimal openPrice;
    private BigDecimal closePrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal preClose;
    private BigDecimal volume;
    private BigDecimal amount;
    private BigDecimal changePercent;
    private String adjustmentType;
    private String sourceCode;
    private LocalDateTime createdAt;
}
