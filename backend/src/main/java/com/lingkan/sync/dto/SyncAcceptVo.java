package com.lingkan.sync.dto;

/**
 * 异步同步任务受理结果（分红/财务同步等无独立任务表的轻量同步）。
 */
public record SyncAcceptVo(
        /** 任务标识（受理成功时有值；被拒绝时为 null，序列化时缺省） */
        String jobId,
        boolean accepted,
        /** 本次同步的股票数量 */
        int count,
        String message
) {

    public static SyncAcceptVo accepted(String jobId, int count, String message) {
        return new SyncAcceptVo(jobId, true, count, message);
    }

    public static SyncAcceptVo rejected(String message) {
        return new SyncAcceptVo(null, false, 0, message);
    }
}
