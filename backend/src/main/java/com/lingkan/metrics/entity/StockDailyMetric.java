package com.lingkan.metrics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日指标快照，用于历史走势图。
 */
@Data
@TableName("stock_daily_metrics")
public class StockDailyMetric {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stockId;
    private String metricCode;
    private BigDecimal metricValue;
    private LocalDate tradeDate;
    private String sourceCode;
    private String calculationVersion;
    private LocalDateTime createdAt;
}
