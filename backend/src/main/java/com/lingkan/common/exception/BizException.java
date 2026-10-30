package com.lingkan.common.exception;

import com.lingkan.common.response.ApiCode;

/**
 * 业务异常。所有可预期错误统一以此抛出，由全局异常处理器转换为 ApiResponse。
 */
public class BizException extends RuntimeException {

    private final ApiCode apiCode;

    public BizException(ApiCode apiCode) {
        super(apiCode.message());
        this.apiCode = apiCode;
    }

    public BizException(ApiCode apiCode, String message) {
        super(message);
        this.apiCode = apiCode;
    }

    public ApiCode apiCode() {
        return apiCode;
    }
}
