package com.lingkan.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据采集日志。
 */
@Data
@TableName("data_sync_logs")
public class DataSyncLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerCode;
    private String metricCode;
    /** QUOTE / DAILY / FINANCIAL / DIVIDEND / METRIC */
    private String syncType;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    /** RUNNING / SUCCESS / PARTIAL / FAILED */
    private String syncStatus;
    private String errorMessage;
}
