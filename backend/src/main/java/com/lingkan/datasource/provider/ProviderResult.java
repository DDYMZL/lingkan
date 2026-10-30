package com.lingkan.datasource.provider;

/**
 * 单指标取数结果。无论成功失败都必须携带来源与时间，保证数据可追溯。
 */
public record ProviderResult(
        String stockCode,
        String metricCode,
        String sourceCode,
        String rawValue,
        java.math.BigDecimal value,
        String unit,
        java.time.LocalDateTime dataTime,
        java.time.LocalDateTime fetchTime,
        Status status,
        String message
) {

    public enum Status {
        OK,
        MISSING,
        ERROR,
        PENDING
    }

    public static ProviderResult ok(String stockCode, String metricCode, String sourceCode,
                                    String rawValue, java.math.BigDecimal value, String unit,
                                    java.time.LocalDateTime dataTime) {
        return new ProviderResult(stockCode, metricCode, sourceCode, rawValue, value, unit,
                dataTime, java.time.LocalDateTime.now(), Status.OK, null);
    }

    public static ProviderResult missing(String stockCode, String metricCode, String sourceCode,
                                         String message) {
        return new ProviderResult(stockCode, metricCode, sourceCode, null, null, null, null,
                java.time.LocalDateTime.now(), Status.MISSING, message);
    }

    public static ProviderResult error(String stockCode, String metricCode, String sourceCode,
                                       String message) {
        return new ProviderResult(stockCode, metricCode, sourceCode, null, null, null, null,
                java.time.LocalDateTime.now(), Status.ERROR, message);
    }
}
