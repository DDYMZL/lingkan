package com.lingkan.datasource.provider.impl;

import com.lingkan.datasource.provider.ProviderHealth;
import com.lingkan.datasource.provider.StockDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 尚未接入或不可用的数据源占位实现。
 *
 * <p>设计意图：把"已登记但当前不可用"的数据源显式建模，避免业务层误判为可用，
 * 也避免为了凑数量而编造接口。所有方法都返回真实状态而不是假数据。
 */
public class PendingProvider implements StockDataProvider {

    private static final Logger log = LoggerFactory.getLogger(PendingProvider.class);

    private final String providerCode;
    private final String providerName;
    private final String unavailableReason;

    public PendingProvider(String providerCode, String providerName, String unavailableReason) {
        this.providerCode = providerCode;
        this.providerName = providerName;
        this.unavailableReason = unavailableReason;
    }

    @Override
    public String getProviderCode() {
        return providerCode;
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public boolean supports(String metricCode) {
        return false;
    }

    @Override
    public com.lingkan.datasource.provider.ProviderResult fetch(String stockCode, String metricCode) {
        return com.lingkan.datasource.provider.ProviderResult.error(
                stockCode, metricCode, providerCode, unavailableReason);
    }

    @Override
    public ProviderHealth checkHealth() {
        log.info("数据源 {} 连通性检查：{}", providerCode, unavailableReason);
        return ProviderHealth.down(providerCode, 0L, unavailableReason);
    }

    @Override
    public List<String> supportedMetrics() {
        return List.of();
    }

    public String unavailableReason() {
        return unavailableReason;
    }
}
