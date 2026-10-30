package com.lingkan.datasource.provider;

import java.time.LocalDateTime;

/**
 * 数据源健康检查结果。
 */
public record ProviderHealth(
        String providerCode,
        boolean up,
        long latencyMs,
        String message,
        LocalDateTime checkedAt
) {

    public static ProviderHealth up(String providerCode, long latencyMs, String message) {
        return new ProviderHealth(providerCode, true, latencyMs, message, LocalDateTime.now());
    }

    public static ProviderHealth down(String providerCode, long latencyMs, String message) {
        return new ProviderHealth(providerCode, false, latencyMs, message, LocalDateTime.now());
    }
}
