package com.lingkan.metrics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户指标展示偏好。第一版为单用户使用，但保留 user_id 维度以便后续扩展。
 */
@Data
@TableName("user_metric_preferences")
public class UserMetricPreference {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String metricCode;
    private Boolean enabled;
    private Boolean showInHome;
    private Boolean showInWatchlist;
    private Boolean showInDetail;
    private Integer sortOrder;
    /** 用户指定的数据源，为空表示跟随指标绑定的主数据源 */
    private String selectedSource;
    /** 用户自定义精度，为空表示使用指标默认精度 */
    private Integer precision;
    private String displayName;
    private String unit;
    private Boolean filterable;
    private Boolean sortable;
    private Boolean recordHistory;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
