# 数据源适配架构

> 版本：v1.0
> 日期：2026-10-30
> 前置阅读：`docs/specs/004-data-source/source-research.md`

## 一、设计目标

1. 业务代码不知道任何第三方接口的存在。
2. 新增数据源不改业务代码，只加适配器 + 注册记录。
3. 同一指标可绑定多个源，可对比、可降级。
4. 任何返回值都能追溯到来源与时间。

## 二、分层

```text
业务模块（metrics / stock / technical）
        │  只认 metricCode 与 stockCode
        ▼
ProviderRegistry           providerCode → StockDataProvider
        │
        ▼
StockDataProvider 实现
  ├ TencentProvider       批量行情（GBK）
  ├ EastMoneyProvider     行情/列表/K线/分红/财报（JSON）
  ├ SinaProvider         行情交叉验证（需 Referer）
  └ PendingProvider       cninfo / legulegu / tushare / akshare / baostock
        │
        ▼
RestClient（统一超时、UA、GBK 兜底）
        │
        ▼
外部站点
```

## 三、统一契约

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

`ProviderResult.status` 枚举：

| 值 | 含义 | 前端展示 |
|---|---|---|
| `SUCCESS` | 成功 | 数值 |
| `MISSING` | 上游无此数据/停牌 | 「—」 |
| `TIMEOUT` | 请求超时 | 「超时」 |
| `ERROR` | 上游报错/解析失败 | 「不可用」 |
| `LOSS` | 数据存在但业务不可计算（如亏损 PE） | 「亏损」 |
| `NOT_READY` | 公式待确认 | 「待配置计算公式」 |
| `PROVISIONAL` | 临时结果（当日未收盘） | 数值 + 「临时」角标 |

## 四、字段标准化

每个适配器负责把上游字段转成内部统一语义：

| 统一语义 | 单位 | 腾讯 | 东方财富 |
|---|---|---|---|
| `latest_price` | 元 | `v[3]` 原值 | `f43 / 100` |
| `change_percent` | % | `v[32]` 原值 | `f170 / 100` |
| `volume` | 手 | `v[6]` 手 | `f47` 手 |
| `amount` | 元 | `v[37]` 万元 × 10000 | `f48` 元 |
| `total_market_cap` | 元 | `v[45]` 亿 × 1e8 | `f116` 元 |
| `pb` | 倍 | `v[46]` 原值 | `f167 / 100` |
| `turnover_rate` | % | `v[38]` 原值 | `f168 / 100` |

**每条换算必须在代码注释中写明**，且换算后的单位写入 `ProviderResult.unit`。

## 五、编码处理

腾讯与新浪返回 **GBK**。`RestClient` 默认按 UTF-8 解码会导致中文股票名乱码。

处理方式：适配器使用 `String` 原始字节 + 显式 `new String(bytes, Charset.forName("GBK"))`，或在 `RestClient` 上为这两个源单独配置 `StringHttpMessageConverter` 的默认字符集。

> 当前实现为适配器内显式按 GBK 解码，**已验证不再乱码**。

## 六、限流与超时

```text
RestClient: connectTimeout 5s / readTimeout 5s（lingkan.http.*）

Adapter 层：
  private volatile long lastCallAt;
  synchronized void throttle() {
      long wait = intervalMs - (System.currentTimeMillis() - lastCallAt);
      if (wait > 0) Thread.sleep(wait);
      lastCallAt = System.currentTimeMillis();
  }
```

默认 `intervalMs = 500`。腾讯批量接口单批 ≤ 50 只。

> 技术债 T-TECH-02：该实现为对象级串行，多实例部署时无效，需换 Redis 令牌桶。

## 七、健康检查语义

`checkHealth()` 必须是**真实请求**，不得返回硬编码 true。

| 源 | 判据 |
|---|---|
| tencent | 请求 `sh600519`，返回体含 `v_sh600519=` 且字段数 ≥ 40 |
| eastmoney | 请求单股快照，`rc == 0 && data != null` |
| sina | 带 Referer 请求，返回体含非空 `hq_str_` |
| pending | 直接 `up=false`，`message` 说明「适配器未实现」 |

## 八、降级链

```text
for binding in bindings(metricCode) sorted by priority:
    if (!binding.enabled) continue
    result = registry.find(binding.providerCode)
                     .fetch(stockCode, metricCode)
    if (result.status == SUCCESS) {
        if (!binding.isPrimary) result.markDegradedFrom(primaryCode)
        return result
    }
return MISSING
```

**降级必须标注**：`ProviderResult` 携带 `degradedFrom` 字段，前端在指标值旁显示来源徽标。不得把备用源数据标成主源。

## 九、原始报文留存

`ProviderResult.rawPayload` 保存上游原始片段（截断 2000 字符）。落库到 `stock_metric_values.raw_payload`，用于：
- 字段口径变更后的回溯比对
- 解析 bug 排查

## 十、新增数据源的操作步骤

1. 在 `datasource/provider/impl/` 新增 `@Component` 实现 `StockDataProvider`（行情类再实现 `QuoteProvider`）。
2. 实现 `supportedMetrics()`，返回标准 `metricCode` 列表。
3. 在 V 迁移脚本（新版本号）中插入 `data_providers` 记录。
4. 如需参与取数，插入 `metric_source_bindings`。
5. 在小程序「数据源配置」中启用并测试连接。

**全程不改任何业务模块代码。**

## 十一、合规与风险

| 风险 | 说明 |
|---|---|
| 接口非公开文档化 | 字段可能随时变更，适配器必须有缺失兜底 |
| 反爬 | 出现封禁应立即降频或停用，不得绕过 |
| 授权 | 仅个人使用、低频访问、不对外分发 |
| 乐咕乐股 | 实测 404，不得在 UI 上宣称支持历史股息率 |
