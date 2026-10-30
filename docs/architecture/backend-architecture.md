# 后端架构（Java / Spring Boot）

> 版本：v1.0
> 日期：2026-10-30

## 一、技术基线

Java 25 · Spring Boot 4.1.2 · Maven · MyBatis-Plus 3.5.18.1（boot4 starter）· openGauss JDBC 6.0.6 · Caffeine · Spring Scheduling · Springdoc 3.2.0。

## 二、包结构

```text
com.lingkan
├── LingKanApplication.java
├── common
│   ├── config        WebConfig, CacheConfig, MybatisPlusConfig, AsyncConfig,
│   │                 OpenApiConfig, HttpClientConfig, RequestTraceFilter,
│   │                 UserContextInterceptor, DbMigrationRunner
│   ├── exception     BizException, GlobalExceptionHandler
│   ├── response      ApiResponse, PageResult, ApiCode
│   └── util          UserContext, CalcVersion, DecimalUtils
├── stock             StockController, StockService, entity, mapper, dto
├── market            MarketController, MarketService, dto
├── metrics           MetricController, UserMetricPreferenceController,
│                     MetricDefinitionService, MetricPreferenceService,
│                     MetricValueService, QuoteSnapshotService, entity, mapper, dto
├── datasource        DataSourceController, DataSourceService,
│                     provider/{StockDataProvider, QuoteProvider, ProviderRegistry,
│                              ProviderResult, QuoteResult, ProviderHealth,
│                              impl/{TencentProvider, EastMoneyProvider, SinaProvider,
│                                    PendingProvider, PendingProviderConfig}}
├── dividend          entity, mapper
├── financial         entity, mapper
├── technical         calculator, TechnicalMetricService
├── watchlist         WatchlistController, WatchlistService, entity, mapper
├── history           HistoryService, dto
├── scheduler         QuoteSyncScheduler
└── system            SystemController, SystemService, dto
```

## 三、模块化单体的边界

采用**包级模块化**，不用 Maven 多模块（第一版避免构建复杂度）。

规则：
1. 模块间只通过 `@Service` 接口调用。
2. 不得注入其他模块的 `*Mapper`。
3. `common` 不依赖任何业务模块。
4. `datasource` 不依赖 `metrics`（`ProviderRegistry` 只处理 `providerCode` 与 `metricCode` 字符串）。

> 现状说明：`DataSourceService` 注入了 `metrics.mapper.MetricSourceBindingMapper`（用于查询绑定关系）。这是一处**已知的边界跨越**，原因是绑定表同时被数据源中心与指标中心读取。已记录为技术债 T-TECH-01，后续应改为 `metrics` 模块暴露 `BindingQueryService`。

## 四、请求生命周期

```text
HTTP
 └─► RequestTraceFilter        生成 traceId → MDC
      └─► UserContextInterceptor 解析 X-User-Id → ThreadLocal
           └─► Controller        @Valid 参数校验
                └─► Service      事务/编排
                     └─► Mapper  SQL
                          └─► openGauss
 └─► ApiResponse 包装
 └─► GlobalExceptionHandler  异常统一转换
 └─► ThreadLocal 清理
```

## 五、统一响应与异常

```java
public record ApiResponse<T>(int code, String message, T data, String traceId, LocalDateTime timestamp)
```

`ApiCode` 枚举维护错误码（40001 参数 / 40004 不存在 / 40009 数据源不可用 / 40010 数据缺失 / 40029 计算未就绪 / 50000 内部错误）。

`GlobalExceptionHandler` 处理：
- `BizException` → 对应 code
- `MethodArgumentNotValidException` → 40001 + 字段错误明细
- 其他 `Exception` → 50000，日志记录完整堆栈，响应不含堆栈

## 六、持久层

- MyBatis-Plus：`BaseMapper` + `LambdaQueryWrapper`，无 XML（第一版）。
- 分页插件 `MybatisPlusInterceptor` + `PaginationInnerInterceptor`（DbType.POSTGRE_SQL，openGauss 兼容 PG 分页语法）。
- 实体使用 Lombok `@Data` + `@TableName`，字段与表列名通过 `map-underscore-to-camel-case` 自动映射。
- 不使用 JPA，不使用 Hibernate。

## 七、数据库迁移

`DbMigrationRunner`（`ApplicationRunner`）：
1. 建 `lingkan_schema_version` 表（若不存在）；
2. 扫描 `classpath:db/migration/V*.sql`，按版本号排序；
3. 跳过已成功执行的版本；
4. 每个脚本在独立事务内执行，失败则记录 `success=false` 并抛出，阻断启动。

脚本命名遵循 `V{n}__{description}.sql`，与 Flyway 规范一致，便于未来迁移。

## 八、配置管理

| 文件 | 用途 |
|---|---|
| `application.yml` | 公共配置 + 默认 profile |
| `application-dev.yml` | 本地开发（数据库指向 localhost，日志 DEBUG） |
| `application-prod.yml` | 生产（全部走环境变量） |

关键配置项：

```yaml
lingkan:
  http:
    connect-timeout-ms: 5000
    read-timeout-ms: 5000
  sync:
    enabled: false          # 第一版默认关闭定时采集
    quote-cron: "0 */5 * * * *"
  cors:
    allowed-origins: "*"
  user:
    default-id: local-user
```

敏感信息（数据库密码、Token）只从环境变量读取，不写入配置文件。

## 九、日志

SLF4J + Logback，pattern 含 `traceId`：

```text
%d{HH:mm:ss.SSS} %-5level [%X{traceId:-}] %logger{36} - %msg%n
```

级别：dev=DEBUG，prod=INFO。数据源适配器打 INFO 记录请求耗时与状态，字段缺失打 WARN。

## 十、缓存

`CacheConfig` 定义 Caffeine `CacheManager`，按 cache name 配置 TTL：

| cache | TTL | 最大条目 |
|---|---|---|
| `quote` | 60s | 5000 |
| `metrics` | 300s | 5000 |
| `dividends` | 3600s | 2000 |
| `metricDefs` | 24h | 500 |

## 十一、测试

- JUnit 5 + Mockito（Spring Boot BOM 管理版本）
- 已有：`DecimalUtilsTest`
- 待补：PE 计算（负值/零值）、利润同比 4 类异常、60日新低边界、适配器字段解析

## 十二、待办技术债

| 编号 | 内容 | 影响 |
|---|---|---|
| T-TECH-01 | `DataSourceService` 跨模块直接注入 `MetricSourceBindingMapper` | 破坏模块边界 |
| T-TECH-02 | 适配器限流为简单 sleep，非线程安全令牌桶 | 并发下可能突破限流 |
| T-TECH-03 | 无集成测试（需 openGauss 容器） | 回归风险 |
| T-TECH-04 | Flyway 未使用，自研迁移器功能较简单（无回滚、无 checksum 校验） | 迁移安全性略低 |
