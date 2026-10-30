package com.lingkan.sync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步主任务。一次 FULL / INCREMENTAL / SINGLE / RETRY 执行对应一条记录。
 */
@Data
@TableName("stock_sync_task")
public class StockSyncTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskNo;
    /** FULL / INCREMENTAL / SINGLE / RETRY */
    private String syncType;
    /** PENDING / RUNNING / SUCCESS / PARTIAL_SUCCESS / FAILED */
    private String status;
    /** MANUAL / SCHEDULED */
    private String triggerType;
    private String currentPhase;
    /** 0~100 */
    private Integer progress;

    private Integer totalStocks;
    private Integer successStocks;
    private Integer failedStocks;
    private Integer stockAdded;
    private Integer stockUpdated;
    private Integer stockSkipped;

    private Integer quoteTotal;
    private Integer quoteSuccess;
    private Integer quoteFailed;

    private Integer metricTotal;
    private Integer metricSuccess;
    private Integer metricFailed;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private String errorMessage;
    private String remark;
    private LocalDateTime createdAt;
}
