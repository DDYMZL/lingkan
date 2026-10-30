package com.lingkan;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 玲看股息 LingKan 后端启动类。
 *
 * <p>架构形态：模块化单体（modular monolith），按业务能力分包，不拆分微服务。
 */
@SpringBootApplication
@MapperScan("com.lingkan.**.mapper")
@EnableCaching
@EnableAsync
@EnableScheduling
public class LingKanApplication {

    public static void main(String[] args) {
        SpringApplication.run(LingKanApplication.class, args);
    }
}
