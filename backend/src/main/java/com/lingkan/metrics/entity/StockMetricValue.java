package com.lingkan.metrics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标值（按指标 + 数据源维度保存），是"多来源对比"的数据基础。
 */
@Data
@TableName("stock_metric_values")
public class StockMetricValue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stockId;
    private String metricCode;
    private String sourceCode;
    /** 原始值字符串，保留上游返回原貌 */
    private String rawValue;
    /** 归一化数值，用于计算与排序 */
    private BigDecimal normalizedValue;
    private String unit;
    private LocalDateTime dataTime;
    private LocalDateTime fetchTime;
    /** OK / MISSING / STALE / ERROR / PENDING */
    private String status;
    private String rawPayload;
    private String calculationVersion;
}
