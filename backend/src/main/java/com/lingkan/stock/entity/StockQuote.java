package com.lingkan.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 最新行情快照（按数据源各保留一条，支持多源对比）。
 */
@Data
@TableName("stock_quotes")
public class StockQuote {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stockId;
    private BigDecimal latestPrice;
    private BigDecimal changePercent;
    private BigDecimal changeAmount;
    private BigDecimal openPrice;
    private BigDecimal highPrice;
    private BigDecimal lowPrice;
    private BigDecimal preClose;
    private BigDecimal volume;
    private BigDecimal amount;
    private BigDecimal turnoverRate;
    private BigDecimal totalMarketCap;
    private String sourceCode;
    private LocalDateTime dataTime;
    private LocalDateTime fetchTime;
}
