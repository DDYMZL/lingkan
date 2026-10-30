package com.lingkan.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 本地缓存（Caffeine）。第一版不引入 Redis。
 */
@Configuration
public class CacheConfig {

    @Value("${lingkan.cache.max-size:5000}")
    private long maxSize;

    @Value("${lingkan.cache.ttl-seconds:60}")
    private long ttlSeconds;

    public static final String CACHE_METRICS = "metrics";
    public static final String CACHE_QUOTES = "quotes";
    public static final String CACHE_PROVIDERS = "providers";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .recordStats());
        return manager;
    }
}
