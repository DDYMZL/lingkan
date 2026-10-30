package com.lingkan.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 第三方数据源 HTTP 客户端。
 *
 * <p>统一使用 JDK 内置 HttpClient，避免额外依赖；连接超时与读取超时由各适配器按指标配置覆盖。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient dataSourceHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
