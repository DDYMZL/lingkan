package com.lingkan.sync.dto;

import com.lingkan.sync.entity.StockSyncTask;

/**
 * 提交同步任务后的返回体。接口只返回任务标识，实际执行异步进行。
 */
public record SyncSubmitVo(Long taskId, String taskNo, String syncType, String status, String message) {

    public static SyncSubmitVo of(StockSyncTask task, String message) {
        return new SyncSubmitVo(task.getId(), task.getTaskNo(), task.getSyncType(),
                task.getStatus(), message);
    }
}
