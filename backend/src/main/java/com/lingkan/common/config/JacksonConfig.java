package com.lingkan.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 2 ObjectMapper 配置。
 *
 * <p>Spring Boot 4 已默认切换到 Jackson 3（{@code tools.jackson}），不再自动装配
 * {@code com.fasterxml.jackson.databind.ObjectMapper}。数据源适配器（EastMoneyProvider 等）
 * 仍需用 Jackson 2 解析第三方原始报文，因此在此显式声明该 Bean。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
