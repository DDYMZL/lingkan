package com.lingkan.datasource.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingkan.common.exception.BizException;
import com.lingkan.common.response.ApiCode;
import com.lingkan.datasource.dto.ProviderDto;
import com.lingkan.datasource.dto.ProviderTestResult;
import com.lingkan.datasource.entity.DataProvider;
import com.lingkan.datasource.entity.DataSyncLog;
import com.lingkan.datasource.mapper.DataProviderMapper;
import com.lingkan.datasource.mapper.DataSyncLogMapper;
import com.lingkan.datasource.provider.ProviderHealth;
import com.lingkan.datasource.provider.ProviderRegistry;
import com.lingkan.datasource.provider.StockDataProvider;
import com.lingkan.metrics.entity.MetricSourceBinding;
import com.lingkan.metrics.mapper.MetricSourceBindingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据源中心服务：注册、启停、健康检查、绑定关系、采集日志。
 */
@Service
public class DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    private final DataProviderMapper providerMapper;
    private final DataSyncLogMapper syncLogMapper;
    private final MetricSourceBindingMapper bindingMapper;
    private final ProviderRegistry registry;

    public DataSourceService(DataProviderMapper providerMapper,
                             DataSyncLogMapper syncLogMapper,
                             MetricSourceBindingMapper bindingMapper,
                             ProviderRegistry registry) {
        this.providerMapper = providerMapper;
        this.syncLogMapper = syncLogMapper;
        this.bindingMapper = bindingMapper;
        this.registry = registry;
    }

    public List<ProviderDto> list() {
        List<DataProvider> providers = providerMapper.selectList(
                Wrappers.<DataProvider>lambdaQuery().orderByAsc(DataProvider::getSortOrder));
        List<ProviderDto> result = new ArrayList<>(providers.size());
        for (DataProvider provider : providers) {
            result.add(toDto(provider));
        }
        return result;
    }

    public ProviderDto get(String providerCode) {
        return toDto(requireProvider(providerCode));
    }

    /**
     * 连通性测试：真实发起一次上游请求，并把结果写回健康状态。
     */
    @Transactional
    public ProviderTestResult test(String providerCode) {
        DataProvider provider = requireProvider(providerCode);
        StockDataProvider adapter = registry.find(providerCode).orElse(null);
        if (adapter == null) {
            ProviderTestResult result = new ProviderTestResult(providerCode, false, 0L,
                    LocalDateTime.now(), "未找到该数据源的适配器实现");
            markFailure(provider, result.message());
            return result;
        }
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            ProviderTestResult result = new ProviderTestResult(providerCode, false, 0L,
                    LocalDateTime.now(), "数据源已停用，请先启用再测试");
            return result;
        }
        ProviderHealth health = adapter.checkHealth();
        if (health.up()) {
            provider.setHealthStatus("UP");
            provider.setLastSuccessAt(LocalDateTime.now());
        } else {
            provider.setHealthStatus("DOWN");
            provider.setLastFailureAt(LocalDateTime.now());
        }
        providerMapper.updateById(provider);
        log.info("数据源 {} 测试完成: up={}, {}ms, {}", providerCode, health.up(),
                health.latencyMs(), health.message());
        return new ProviderTestResult(providerCode, health.up(), health.latencyMs(),
                health.checkedAt(), health.message());
    }

    @Transactional
    public ProviderDto enable(String providerCode) {
        DataProvider provider = requireProvider(providerCode);
        provider.setEnabled(true);
        providerMapper.updateById(provider);
        return toDto(provider);
    }

    @Transactional
    public ProviderDto disable(String providerCode) {
        DataProvider provider = requireProvider(providerCode);
        provider.setEnabled(false);
        providerMapper.updateById(provider);
        return toDto(provider);
    }

    public List<DataSyncLog> logs(String providerCode, int limit) {
        requireProvider(providerCode);
        return syncLogMapper.selectList(Wrappers.<DataSyncLog>lambdaQuery()
                .eq(DataSyncLog::getProviderCode, providerCode)
                .orderByDesc(DataSyncLog::getStartedAt)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }

    public List<MetricSourceBinding> allBindings() {
        return bindingMapper.selectList(Wrappers.<MetricSourceBinding>lambdaQuery()
                .orderByAsc(MetricSourceBinding::getMetricCode)
                .orderByAsc(MetricSourceBinding::getPriority));
    }

    public List<MetricSourceBinding> bindingsOf(String metricCode) {
        return bindingMapper.selectList(Wrappers.<MetricSourceBinding>lambdaQuery()
                .eq(MetricSourceBinding::getMetricCode, metricCode)
                .orderByAsc(MetricSourceBinding::getPriority));
    }

    /**
     * 保存指标-数据源绑定（用于切换主数据源）。同一指标最多一个主数据源。
     */
    @Transactional
    public void saveBindings(List<MetricSourceBinding> bindings) {
        for (MetricSourceBinding binding : bindings) {
            bindingMapper.updateById(binding);
        }
    }

    private void markFailure(DataProvider provider, String message) {
        provider.setHealthStatus("DOWN");
        provider.setLastFailureAt(LocalDateTime.now());
        providerMapper.updateById(provider);
        log.warn("数据源 {} 不可用: {}", provider.getProviderCode(), message);
    }

    private DataProvider requireProvider(String providerCode) {
        DataProvider provider = providerMapper.selectOne(
                Wrappers.<DataProvider>lambdaQuery().eq(DataProvider::getProviderCode, providerCode));
        if (provider == null) {
            throw new BizException(ApiCode.PROVIDER_NOT_FOUND, "数据源不存在: " + providerCode);
        }
        return provider;
    }

    private ProviderDto toDto(DataProvider provider) {
        List<String> metrics = registry.find(provider.getProviderCode())
                .map(StockDataProvider::supportedMetrics)
                .orElse(List.of());
        return new ProviderDto(
                provider.getProviderCode(),
                provider.getProviderName(),
                provider.getProviderType(),
                provider.getBaseUrl(),
                Boolean.TRUE.equals(provider.getEnabled()),
                Boolean.TRUE.equals(provider.getRequiresToken()),
                provider.getRequestIntervalMs() == null ? 1000 : provider.getRequestIntervalMs(),
                provider.getHealthStatus(),
                provider.getLastSuccessAt(),
                provider.getLastFailureAt(),
                metrics,
                metrics.size(),
                provider.getDescription(),
                Boolean.TRUE.equals(provider.getVerified())
        );
    }
}
