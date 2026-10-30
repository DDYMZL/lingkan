package com.lingkan.sync.dto;

import com.lingkan.sync.entity.StockSyncTaskDetail;

import java.time.LocalDateTime;

/**
 * 失败明细视图：支持按股票 / 数据源 / 指标定位问题，并作为重试依据。
 */
public record StockSyncDetailVo(
        Long id,
        String stockCode,
        String providerCode,
        String metricCode,
        String syncType,
        String status,
        Integer retryCount,
        String errorMessage,
        LocalDateTime createdAt
) {

    public static StockSyncDetailVo of(StockSyncTaskDetail d) {
        return new StockSyncDetailVo(d.getId(), d.getStockCode(), d.getProviderCode(),
                d.getMetricCode(), d.getSyncType(), d.getStatus(),
                d.getRetryCount() == null ? 0 : d.getRetryCount(), d.getErrorMessage(),
                d.getCreatedAt());
    }
}
