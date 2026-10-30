package com.lingkan.datasource.dto;

import java.time.LocalDateTime;

/**
 * 数据源连通性测试结果。
 */
public record ProviderTestResult(
        String providerCode,
        boolean success,
        long latencyMs,
        LocalDateTime testedAt,
        String message
) {
}
