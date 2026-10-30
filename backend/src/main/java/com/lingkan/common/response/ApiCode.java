package com.lingkan.common.response;

/**
 * 业务错误码。分段规则：4xxxx 客户端问题，5xxxx 服务端/上游问题。
 */
public enum ApiCode {

    SUCCESS(0, "成功"),

    BAD_REQUEST(40000, "请求参数不合法"),
    STOCK_NOT_FOUND(40401, "股票不存在"),
    METRIC_NOT_FOUND(40402, "指标不存在"),
    PROVIDER_NOT_FOUND(40403, "数据源不存在"),
    PREFERENCE_NOT_FOUND(40404, "指标偏好不存在"),

    METRIC_FORMULA_PENDING(40901, "该指标公式尚未确认，暂不参与计算"),
    PROVIDER_DISABLED(40902, "数据源已停用"),
    PROVIDER_RATE_LIMITED(42901, "数据源请求过于频繁"),

    PROVIDER_UNAVAILABLE(50201, "数据源不可用"),
    PROVIDER_TIMEOUT(50401, "数据源请求超时"),
    DATA_NOT_AVAILABLE(50404, "暂无该数据"),
    INTERNAL_ERROR(50000, "服务内部错误");

    private final int code;
    private final String message;

    ApiCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
