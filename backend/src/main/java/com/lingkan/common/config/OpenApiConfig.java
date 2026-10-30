package com.lingkan.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置。仅供本地调试，生产环境建议通过 profile 关闭。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI lingKanOpenApi() {
        return new OpenAPI().info(new Info()
                .title("玲看股息 LingKan API")
                .version("v1")
                .description("A 股股票数据查询与分析服务。所有数据均标注来源与采集时间。")
                .contact(new Contact().name("LingKan")));
    }
}
