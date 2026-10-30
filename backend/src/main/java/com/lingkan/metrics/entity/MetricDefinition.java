package com.lingkan.metrics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指标定义（指标库）。指标清单是系统元数据，不允许写死在前端或业务代码中。
 */
@Data
@TableName("metric_definitions")
public class MetricDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String metricCode;
    private String metricName;
    /** basic / quote / valuation / dividend / financial / technical */
    private String metricCategory;
    /** DECIMAL / INTEGER / STRING / BOOLEAN / DATE / PERCENT */
    private String dataType;
    private String unit;
    private String description;
    /** SOURCE / CALCULATED / DERIVED */
    private String calculationType;
    private String calculationExpression;
    /** READY / PENDING / DISABLED */
    private String calculationStatus;
    private Boolean enabled;
    private Integer defaultPrecision;
    private Integer sortOrder;
    /** 待确认指标需用户回答的问题，分号分隔 */
    private String pendingQuestions;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
