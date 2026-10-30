package com.lingkan.datasource.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据源展示对象。注意：不包含任何 Token / 密钥字段。
 */
public record ProviderDto(
        String providerCode,
        String providerName,
        String providerType,
        String baseUrl,
        boolean enabled,
        boolean requiresToken,
        int requestIntervalMs,
        String healthStatus,
        LocalDateTime lastSuccessAt,
        LocalDateTime lastFailureAt,
        List<String> supportedMetrics,
        int metricCount,
        String description,
        boolean verified
) {
}
