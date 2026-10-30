package com.lingkan.sync.dto;

import com.lingkan.sync.entity.StockSyncTask;

import java.time.LocalDateTime;

/**
 * 同步任务进度视图。
 *
 * @param progress 0~100，按"清单 10% + 行情 35% + 指标 55%"加权估算
 */
public record StockSyncTaskVo(
        Long taskId,
        String taskNo,
        String syncType,
        String status,
        String triggerType,
        String currentPhase,
        Integer progress,
        Integer totalStocks,
        Integer successStocks,
        Integer failedStocks,
        Integer stockAdded,
        Integer stockUpdated,
        Integer stockSkipped,
        Integer quoteTotal,
        Integer quoteSuccess,
        Integer quoteFailed,
        Integer metricTotal,
        Integer metricSuccess,
        Integer metricFailed,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long durationMs,
        String errorMessage,
        String remark,
        LocalDateTime createdAt
) {

    public static StockSyncTaskVo of(StockSyncTask t) {
        return new StockSyncTaskVo(t.getId(), t.getTaskNo(), t.getSyncType(), t.getStatus(),
                t.getTriggerType(), t.getCurrentPhase(), nz(t.getProgress()),
                nz(t.getTotalStocks()), nz(t.getSuccessStocks()), nz(t.getFailedStocks()),
                nz(t.getStockAdded()), nz(t.getStockUpdated()), nz(t.getStockSkipped()),
                nz(t.getQuoteTotal()), nz(t.getQuoteSuccess()), nz(t.getQuoteFailed()),
                nz(t.getMetricTotal()), nz(t.getMetricSuccess()), nz(t.getMetricFailed()),
                t.getStartTime(), t.getEndTime(), t.getDurationMs(), t.getErrorMessage(),
                t.getRemark(), t.getCreatedAt());
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
