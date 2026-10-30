# 004-数据源中心 · 技术设计

> 版本：v1.0
> 日期：2026-10-30
> 状态：`approved`

## 一、总体架构

```text
Controller 层           DataSourceController
                              │
                              ▼
Service 层              DataSourceService  ──►  MetricSourceBindingMapper
                              │                 DataProviderMapper
                              │                 DataSyncLogMapper
                              ▼
Provider 层             ProviderRegistry  ──►  Map<providerCode, StockDataProvider>
                              │
                              ▼
Adapter 实现            TencentProvider / EastMoneyProvider / SinaProvider
                        PendingProvider（cninfo / legulegu / tushare / akshare / baostock）
                              │
                              ▼
外部 HTTP               RestClient（统一超时 + 限流 + UA）
```

**核心约束**：Controller 不得出现任何 `restClient.get(...)` / `URL(...)` / 第三方域名常量。所有外部访问只允许出现在 `datasource/provider/impl/*` 中。

## 二、统一接口

```java
public interface StockDataProvider {
    String getProviderCode();
    String getProviderName();
    boolean supports(String metricCode);
    List<String> supportedMetrics();
    ProviderResult fetch(String stockCode, String metricCode);
    ProviderHealth checkHealth();
}

public interface QuoteProvider extends StockDataProvider {
    List<QuoteResult> fetchQuotes(List<String> stockCodes);
}
```

`ProviderResult` 统一字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `providerCode` | String | 来源 |
| `metricCode` | String | 指标 |
| `stockCode` | String | 股票 |
| `rawValue` | String | 原始值（字符串，保留原始精度） |
| `normalizedValue` | BigDecimal | 标准化后的数值 |
| `unit` | String | 单位 |
| `dataTime` | LocalDateTime | 数据所属时间 |
| `fetchTime` | LocalDateTime | 实际采集时间 |
| `status` | enum | `SUCCESS` / `MISSING` / `TIMEOUT` / `ERROR` / `NOT_SUPPORTED` |
| `rawPayload` | String | 原始报文片段（截断至安全长度） |
| `message` | String | 失败原因 |

**关键设计**：`dataTime` 与 `fetchTime` 必须分别赋值，禁止复用同一个时间。这是宪法原则四（数据可追溯）的落地点。

## 三、注册机制

`ProviderRegistry` 在启动时通过 Spring 注入所有 `StockDataProvider` 实现，构建 `providerCode → adapter` 的不可变 Map。

- 新增数据源 = 新增一个 `@Component` 实现类 + 一条 `data_providers` 记录，**不需要改任何业务代码**。
- 数据库中已注册但无适配器实现的数据源（如 `legulegu`），`registry.find()` 返回空，UI 显示为「未实现」，测试连接返回失败并说明原因。

## 四、健康检查

`checkHealth()` 实现为**真实请求**：

- 腾讯：请求 `sh600519` 单只，判断返回行数 > 0
- 东方财富：请求单股快照，判断 `rc == 0 && data != null`
- 新浪：带 Referer 请求，判断返回非空
- 未实现源：直接返回 `up=false` + "适配器未实现"

测试结果写回 `data_providers.health_status` / `last_success_at` / `last_failure_at`。

## 五、限流与超时

| 层次 | 手段 |
|---|---|
| 连接层 | `RestClient` 统一设置 connectTimeout / readTimeout（默认 5s，来自 `lingkan.http.*`） |
| 数据源层 | 每个 `data_providers.request_interval_ms` 生效；适配器内部用 `volatile long lastCallAt` + `Thread.sleep` 补齐间隔 |
| 任务层 | `lingkan.sync.enabled` 默认 `false`，避免开发期误触全市场采集 |

## 六、多源对比实现

`GET /api/v1/stocks/{stockCode}/metrics/{metricCode}/sources`

流程：
1. 从 `metric_source_bindings` 取该指标所有 `enabled=true` 的绑定，按 `priority` 升序。
2. 逐个绑定调用 `registry.find(providerCode).fetch(...)`。
3. 主源（`is_primary=true`）的值作为 `selectedValue`。
4. 其余来源计算 `diff = value - selectedValue`（仅当双方均 `SUCCESS` 且可计算）。
5. **不做任何聚合运算**，如求均值、取中位数。

返回结构：

```json
{
  "stockCode": "600519",
  "metricCode": "latest_price",
  "values": [
    { "source": "tencent", "value": 1392.05, "unit": "元",
      "dataTime": "2026-10-30T15:16:55", "fetchTime": "2026-10-30T15:17:02",
      "status": "SUCCESS", "diffFromPrimary": null },
    { "source": "eastmoney", "value": 1392.05, "unit": "元",
      "dataTime": "2026-10-30T15:16:00", "fetchTime": "2026-10-30T15:17:03",
      "status": "SUCCESS", "diffFromPrimary": 0.00 }
  ],
  "selectedSource": "tencent",
  "selectedValue": 1392.05
}
```

## 七、Token 安全

- Token 存 `data_providers.token_encrypted`（第一版为服务端数据库字段，不返回给前端）。
- `ProviderDto` **不包含** token 字段。
- 前端只能提交 Token（`PUT /data-sources/{code}` 的 `token` 字段），读取时永不回显，只返回 `hasToken: true/false`。

## 八、采集日志

每次批量采集由 `scheduler` 模块在 `data_sync_logs` 写入一行，含 total/success/failed。`DataSourceService.logs(code, limit)` 返回最近记录，limit 上限 100。

## 九、与数据库的对应

| 表 | 用途 |
|---|---|
| `data_providers` | 数据源注册信息、健康状态、限流参数 |
| `metric_source_bindings` | 指标 × 数据源绑定与优先级 |
| `data_sync_logs` | 采集任务日志 |
| `stock_metric_values` | 多源指标值落地（含 raw_payload） |

## 十、偏离与风险

| 项 | 说明 |
|---|---|
| 未引入 Resilience4j | 第一版用「优先级 + try/catch + 状态标记」实现降级，不引入熔断器，避免额外依赖 |
| 未使用消息队列 | Python 采集器接入时改 HTTP 同步回调，暂不引入 MQ |
| 限流为单机版 | 多实例部署时需换 Redis 令牌桶，当前为个人单机使用，不实现 |
