package com.lingkan.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源定义。Token 等敏感配置不入库明文字段，改由环境变量/密钥管理提供。
 */
@Data
@TableName("data_providers")
public class DataProvider {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String providerCode;
    private String providerName;
    /** HTTP_API / PYTHON_COLLECTOR / SYSTEM_CALC */
    private String providerType;
    private String baseUrl;
    private Boolean enabled;
    private Boolean requiresToken;
    private Integer requestIntervalMs;
    private String rateLimitConfig;
    /** UP / DOWN / DEGRADED / UNKNOWN */
    private String healthStatus;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime lastFailureAt;
    /** 支持指标编码，逗号分隔 */
    private String supportedMetrics;
    private String description;
    /** 是否经过真实接口验证 */
    private Boolean verified;
    private Integer sortOrder;
}
