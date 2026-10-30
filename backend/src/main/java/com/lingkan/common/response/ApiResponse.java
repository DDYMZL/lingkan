package com.lingkan.common.response;

import org.slf4j.MDC;

/**
 * 统一响应结构。
 *
 * @param code      业务码，0 表示成功
 * @param message   提示信息
 * @param data      业务数据
 * @param timestamp 服务端时间戳（毫秒）
 * @param traceId   链路标识，便于排查
 */
public record ApiResponse<T>(int code, String message, T data, long timestamp, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, System.currentTimeMillis(), currentTraceId());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "ok", null, System.currentTimeMillis(), currentTraceId());
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, System.currentTimeMillis(), currentTraceId());
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
