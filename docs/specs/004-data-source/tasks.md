# 004-数据源中心 · 开发任务拆解

> 版本：v1.0
> 日期：2026-10-30

## Phase 1-A 数据源研究与验证（已完成）

| 编号 | 任务 | 状态 |
|---|---|---|
| T4-01 | 实测东方财富 5 个接口（列表/快照/K线/分红/财报） | ✅ 完成，见 `source-research.md` |
| T4-02 | 实测腾讯财经批量行情接口，确认字段位置与 GBK 编码 | ✅ 完成 |
| T4-03 | 实测新浪行情接口，确认必须携带 Referer | ✅ 完成 |
| T4-04 | 实测巨潮公告查询接口 | ✅ 完成（仅公告索引） |
| T4-05 | 实测乐咕乐股候选接口 | ✅ 完成（404，判定不可用） |
| T4-06 | 实测 Tushare 接口可达性与鉴权要求 | ✅ 完成（需 Token） |
| T4-07 | 三源字段口径交叉核对（600519 最新价/PB/涨跌幅） | ✅ 完成，三源一致 |
| T4-08 | 输出 `source-research.md` | ✅ 完成 |

## Phase 1-B 数据源中心骨架（已完成）

| 编号 | 任务 | 状态 |
|---|---|---|
| T4-10 | `DataProvider` / `DataSyncLog` 实体与 Mapper | ✅ |
| T4-11 | `StockDataProvider` / `QuoteProvider` 接口定义 | ✅ |
| T4-12 | `ProviderResult` / `QuoteResult` / `ProviderHealth` 统一返回结构 | ✅ |
| T4-13 | `ProviderRegistry` 自动注册机制 | ✅ |
| T4-14 | `TencentProvider` 实现（GBK 解码、批量） | ✅ |
| T4-15 | `EastMoneyProvider` 实现（列表/快照/K线/分红/财报） | ✅ |
| T4-16 | `SinaProvider` 实现（Referer 头） | ✅ |
| T4-17 | `PendingProvider` 占位实现（cninfo/legulegu/tushare/akshare/baostock） | ✅ |
| T4-18 | `DataSourceService`：列表/详情/启停/测试/日志/绑定 | ✅ |
| T4-19 | `DataSourceController`：`/api/v1/data-sources/*` | ✅ |
| T4-20 | V3 迁移：`data_providers` 与 `metric_source_bindings` 初始化数据 | ✅ |
| T4-21 | 统一 `RestClient` 配置（超时） | ✅ |

## Phase 1-C 待完成（Phase 3 补强）

| 编号 | 任务 | 状态 |
|---|---|---|
| T4-30 | 适配器级限流（按 `request_interval_ms` 精确控制） | ⏳ 待完善（当前为固定间隔，未做令牌桶） |
| T4-31 | `stock_metric_values` 落库与 `raw_payload` 截断策略 | ⏳ |
| T4-32 | 数据源优先级降级链的完整实现（含降级标注） | ⏳ |
| T4-33 | 采集日志由 scheduler 实际写入 | ⏳（scheduler 骨架已建，默认关闭） |
| T4-34 | 北交所 `secid` 规则实测确认 | ⏳ 待验证 |
| T4-35 | Tushare Token 接入（待用户提供） | ⏸ 阻塞于用户 |
| T4-36 | 乐咕乐股替代源确认 | ⏸ 阻塞于用户决策 |
| T4-37 | Python AKShare 采集容器 | ⏸ 第一版不做 |

## 依赖关系

```text
T4-10 ──► T4-11 ──► T4-14/15/16 ──► T4-13 ──► T4-18 ──► T4-19
                                          │
T4-20 ────────────────────────────────────┘
T4-13 ──► T4-32 ──► T4-30
T4-19 ──► T4-31 ──► T4-33
```
