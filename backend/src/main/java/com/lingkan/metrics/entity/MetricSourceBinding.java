package com.lingkan.metrics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 指标与数据源绑定关系。一个指标可绑定多个数据源，其中最多一个主数据源。
 */
@Data
@TableName("metric_source_bindings")
public class MetricSourceBinding {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String metricCode;
    private String providerCode;
    /** 数值越小优先级越高 */
    private Integer priority;
    private Boolean enabled;
    private Boolean isPrimary;
    private Integer timeoutMs;
    private Integer cacheSeconds;
}
