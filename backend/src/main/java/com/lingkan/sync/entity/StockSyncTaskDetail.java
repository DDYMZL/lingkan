package com.lingkan.sync.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步明细：记录股票清单、行情批次、单股指标三类失败，用于精准重试。
 */
@Data
@TableName("stock_sync_task_detail")
public class StockSyncTaskDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;
    private String stockCode;
    private String providerCode;
    private String metricCode;
    /** STOCK / QUOTE / METRIC */
    private String syncType;
    /** SUCCESS / FAILED */
    private String status;
    private Integer retryCount;
    private String errorMessage;
    private LocalDateTime createdAt;

    public static StockSyncTaskDetail failed(Long taskId, String syncType, String stockCode,
                                             String providerCode, String metricCode, String message) {
        StockSyncTaskDetail detail = new StockSyncTaskDetail();
        detail.setTaskId(taskId);
        detail.setSyncType(syncType);
        detail.setStockCode(stockCode);
        detail.setProviderCode(providerCode);
        detail.setMetricCode(metricCode);
        detail.setStatus("FAILED");
        detail.setRetryCount(0);
        detail.setErrorMessage(message == null ? null
                : message.substring(0, Math.min(message.length(), 500)));
        detail.setCreatedAt(LocalDateTime.now());
        return detail;
    }
}
