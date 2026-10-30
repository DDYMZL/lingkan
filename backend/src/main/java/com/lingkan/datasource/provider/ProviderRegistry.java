package com.lingkan.datasource.provider;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源适配器注册表。业务层只依赖接口，通过编码获取具体实现。
 */
@Component
public class ProviderRegistry {

    private final Map<String, StockDataProvider> providers = new LinkedHashMap<>();

    public ProviderRegistry(List<StockDataProvider> providerList) {
        for (StockDataProvider provider : providerList) {
            providers.put(provider.getProviderCode(), provider);
        }
    }

    public Optional<StockDataProvider> find(String providerCode) {
        return Optional.ofNullable(providers.get(providerCode));
    }

    public List<StockDataProvider> all() {
        return List.copyOf(providers.values());
    }

    public Optional<QuoteProvider> findQuoteProvider(String providerCode) {
        StockDataProvider provider = providers.get(providerCode);
        return provider instanceof QuoteProvider quoteProvider
                ? Optional.of(quoteProvider)
                : Optional.empty();
    }
}
