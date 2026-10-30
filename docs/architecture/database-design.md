# 数据库架构设计说明

> 版本：v1.0
> 日期：2026-10-30
> 完整表结构见 `docs/database/schema.md`，本文件说明**设计取舍与原则**。

## 一、为什么用 openGauss

原文档强制要求 openGauss，理由可归纳为：

1. 国产数据库，符合信创场景；
2. 兼容 PostgreSQL 协议，生态工具可用；
3. 支持 `NUMERIC` 高精度数值，适合金融数据。

本项目据此选定 `enmotech/opengauss:5.0.0` 容器镜像 + `org.opengauss:opengauss-jdbc:6.0.6` 驱动。

## 二、数据建模原则

### 2.1 三类数据分离

| 类别 | 表 | 特性 |
|---|---|---|
| **实体数据** | `stocks` | 变化慢，可 UPDATE |
| **时序数据** | `stock_quotes` / `stock_daily_quotes` / `stock_metric_values` / `stock_daily_metrics` | 追加为主，禁止覆盖历史 |
| **配置数据** | `metric_definitions` / `metric_source_bindings` / `data_providers` / `user_*` | 可 UPDATE |

这样区分后，"不得删除历史数据"的约束只作用于时序表。

### 2.2 最新快照与历史分离

`stock_quotes` 存"每源每股票一行最新"，`stock_daily_quotes` 存全部历史。

原因：列表页与详情页需要"最新价"，如果每次都从历史表 `ORDER BY trade_date DESC LIMIT 1`，在 5000 股票 × 250 天数据量下会难以接受。

### 2.3 多源值不聚合

`stock_metric_values` 按 `(stock_id, metric_code, source_code, data_time)` 存每一份原始取值。

**没有**"合并后的指标表"。展示用哪一份由 `metric_source_bindings.is_primary` + 用户 `selected_source` 决定。这是"不得混合数据源"原则的结构性保证。

### 2.4 计算版本可追溯

`stock_daily_metrics.calculation_version` + `CalcVersion` 常量类。

当"近3天创60日新低"的口径从"含当日"改为"不含当日"时：
- 新建版本 `v2-low60-3d-excl`
- 重算并覆盖同一行，`calculation_version` 更新为 v2
- `docs/changes/changelog.md` 记录变更

> ⚠️ 此处"覆盖"与宪法原则六存在张力。已记录为待确认项 **Q7-9**：是否改为按版本保留多行历史？

### 2.5 金额与比率精度

| 数据 | 类型 | 理由 |
|---|---|---|
| 价格 | NUMERIC(20,4) | A股最小 0.01，留足余量应对基金/债券扩展 |
| 涨跌额/比率（%） | NUMERIC(12,4) | 最大 ±9999.9999% |
| 市值/成交额 | NUMERIC(24,4) | 万亿级 |
| 成交量 | NUMERIC(24,2) | 手，无需 4 位小数 |
| EPS | NUMERIC(16,6) | 每股收益可能很小 |
| 每股分红 | NUMERIC(20,6) | 部分公司分红到 0.0001 元/股 |

**禁止 FLOAT/DOUBLE**：`0.1 + 0.2 != 0.3` 在股息率累加时会放大误差。

### 2.6 时间语义

| 字段 | 含义 | 示例 |
|---|---|---|
| `data_time` | 数据所描述的时刻（行情推送时间） | 2026-10-30 15:00:00 |
| `fetch_time` | 我方实际抓取时刻 | 2026-10-30 15:00:03 |
| `trade_date` | 交易日 | 2026-10-30 |
| `created_at` | 记录入库时刻 | 2026-10-30 15:00:04 |

**三者不可混用**。前端展示"更新时间"时必须用 `data_time`，调试时看 `fetch_time`。

### 2.7 为什么不用外键

| 理由 | 说明 |
|---|---|
| 批量导入性能 | 全市场 5000+ 股票日线导入时外键检查开销大 |
| 模块边界 | 模块化单体中，跨模块强约束会阻碍后续拆分 |
| 历史数据保留 | 股票退市后基础信息可能被归档，但历史行情需保留 |

代价：引用完整性由应用层保证，需在 Service 层校验 `stock_id` 有效。

### 2.8 索引策略

| 表 | 索引 | 支撑查询 |
|---|---|---|
| `stocks` | `(exchange, stock_code)` UNIQUE | 按代码查 |
| `stocks` | `(stock_name)` | 名称搜索 |
| `stock_daily_quotes` | `(stock_id, trade_date DESC)` | 单股历史序列 |
| `stock_daily_metrics` | `(stock_id, metric_code, trade_date DESC)` | 指标历史序列 |
| `data_sync_logs` | `(provider_code, started_at DESC)` | 日志列表 |
| `user_watchlists` | `(user_id, stock_id)` UNIQUE | 自选判断 |

**未建**全表扫描型索引（如 `stock_metrics.metric_value`），因为筛选排序走应用层内存计算（第一版股票数 5000，可接受）。

## 三、容量估算

| 表 | 单行估算 | 数据量 | 年增长 |
|---|---|---|---|
| `stocks` | 300 B | 5,300 | 少量 |
| `stock_quotes` | 200 B | 5,300 × 源数 | 不增长（覆盖） |
| `stock_daily_quotes` | 150 B | 5,300 × 250 | ~200 MB/年 |
| `stock_daily_metrics` | 100 B | 5,300 × 250 × 指标数 | 指标数 10 → ~1.3 GB/年 |
| `stock_metric_values` | 500 B（含 payload） | 动态 | 需定期归档 `raw_payload` |

结论：个人使用场景下 5 年内单机 openGauss 完全可承载。`raw_payload` 是主要膨胀源，**建议 Phase 4 增加 3 个月归档策略**（归档非删除，符合原则六）。

## 四、迁移策略

| 阶段 | 脚本 | 内容 |
|---|---|---|
| V1 | `V1__init_schema.sql` | 建全部表、约束、索引 |
| V2 | `V2__seed_metric_definitions.sql` | 预置指标定义（含 6 个 PENDING 指标） |
| V3 | `V3__seed_providers_and_bindings.sql` | 预置 8 个数据源 + 指标绑定 |

已执行版本记录在 `lingkan_schema_version`。

**规则**：已发布的迁移脚本不得修改，只能新增更高版本号。这与 Flyway 的 checksum 语义一致（虽然当前迁移器不校验 checksum，但规则必须遵守）。

## 五、待确认

| 编号 | 问题 |
|---|---|
| QDB-1 | `stock_daily_metrics` 口径变更时覆盖还是新增版本行（= Q7-9） |
| QDB-2 | `raw_payload` 归档周期 |
| QDB-3 | 是否需要分区表（按 `trade_date` 月分区）—— 当前数据量下不必要 |
