# LingKan 数据库设计（openGauss）

> 版本：v1.0
> 日期：2026-10-30
> 对应迁移脚本：`backend/src/main/resources/db/migration/V1__init_schema.sql` ~ `V3__seed_providers_and_bindings.sql`

## 一、总体约定

| 项 | 约定 |
|---|---|
| 字符集 | UTF-8 |
| 金额/比率 | `NUMERIC`，禁止 `FLOAT`/`DOUBLE`（避免浮点误差） |
| 时间 | `TIMESTAMP`（无时区），统一存东八区本地时间；API 输出带 `+08:00` 偏移 |
| 日期 | `DATE`（交易日期） |
| 主键 | `BIGSERIAL` |
| 审计字段 | 业务表统一含 `created_at`，可变更表含 `updated_at` |
| 外键 | **不使用数据库外键**，由应用层保证引用完整性（便于批量导入与分模块演进） |
| 删除 | 历史数据表禁止物理删除；用户偏好表可物理删除 |
| 命名 | 表名小写下划线；唯一约束 `uk_*`；索引 `idx_*` |

## 二、ER 关系概览

```text
stocks ─┬─► stock_quotes           (1:N, 每股票每源一行最新快照)
        ├─► stock_daily_quotes     (1:N, 日线)
        ├─► stock_financials       (1:N, 报告期)
        ├─► stock_dividends        (1:N, 分红方案)
        ├─► stock_metric_values    (1:N, 多源指标值)
        ├─► stock_daily_metrics    (1:N, 每日指标快照)
        ├─► user_watchlists        (1:N, 用户自选)
        └─► user_metric_preferences(1:N, 用户指标偏好)

metric_definitions ──► metric_source_bindings ──► data_providers
data_providers ──► data_sync_logs
```

## 三、表定义

### 3.1 `stocks` 股票基础信息

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | |
| `stock_code` | VARCHAR(16) | NOT NULL | 6 位代码 |
| `stock_name` | VARCHAR(64) | NOT NULL | |
| `exchange` | VARCHAR(8) | NOT NULL | `SH` / `SZ` / `BJ` |
| `market` | VARCHAR(16) | | 主板/创业板/科创板/北交所 |
| `board` | VARCHAR(16) | | |
| `industry` | VARCHAR(64) | | 所属行业 |
| `listing_date` | DATE | | 上市日期 |
| `delisting_date` | DATE | | 退市日期 |
| `stock_status` | VARCHAR(16) | NOT NULL DEFAULT 'LISTED' | `LISTED`/`SUSPENDED`/`DELISTED` |
| `is_st` | BOOLEAN | NOT NULL DEFAULT FALSE | |
| `created_at` | TIMESTAMP | NOT NULL DEFAULT now() | |
| `updated_at` | TIMESTAMP | NOT NULL DEFAULT now() | |

约束：`UNIQUE (exchange, stock_code)`
索引：`idx_stocks_name (stock_name)`、`idx_stocks_status (stock_status)`

> **股票代码必须与交易所联合唯一**：不同交易所可能存在相同数字代码。

### 3.2 `stock_quotes` 最新行情

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `stock_id` | BIGINT NOT NULL | |
| `latest_price` | NUMERIC(20,4) | |
| `change_percent` | NUMERIC(12,4) | 涨跌幅 % |
| `change_amount` | NUMERIC(12,4) | |
| `open_price` / `high_price` / `low_price` / `pre_close` | NUMERIC(20,4) | |
| `volume` | NUMERIC(24,2) | 手 |
| `amount` | NUMERIC(24,4) | 元 |
| `turnover_rate` | NUMERIC(12,4) | % |
| `total_market_cap` | NUMERIC(24,4) | 元 |
| `source_code` | VARCHAR(32) NOT NULL | |
| `data_time` | TIMESTAMP | **数据所属时间** |
| `fetch_time` | TIMESTAMP NOT NULL | **实际采集时间** |

约束：`UNIQUE (stock_id, source_code)` — 每个源一只股票只保留最新一条
索引：`idx_quotes_data_time (data_time DESC)`

### 3.3 `stock_daily_quotes` 历史日线

| 字段 | 类型 |
|---|---|
| `id` | BIGSERIAL PK |
| `stock_id` | BIGINT NOT NULL |
| `trade_date` | DATE NOT NULL |
| `open_price` / `close_price` / `high_price` / `low_price` / `pre_close` | NUMERIC(20,4) |
| `volume` / `amount` | NUMERIC(24,2) / NUMERIC(24,4) |
| `change_percent` | NUMERIC(12,4) |
| `adjustment_type` | VARCHAR(8) NOT NULL DEFAULT 'NONE' |
| `source_code` | VARCHAR(32) NOT NULL |

约束：`UNIQUE (stock_id, trade_date, adjustment_type, source_code)`
索引：`idx_daily_quote_stock_date (stock_id, trade_date DESC)`

### 3.4 `stock_financials` 财务数据

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `stock_id` | BIGINT NOT NULL | |
| `report_period` | VARCHAR(16) NOT NULL | `2026Q3` / `2025A` |
| `report_type` | VARCHAR(16) | `Q1`/`H1`/`Q3`/`ANNUAL` |
| `revenue` | NUMERIC(24,4) | 营业收入 |
| `net_profit` | NUMERIC(24,4) | 净利润 |
| `net_profit_parent` | NUMERIC(24,4) | 归母净利润 |
| `revenue_yoy` | NUMERIC(12,4) | % |
| `net_profit_yoy` | NUMERIC(12,4) | % |
| `net_profit_parent_yoy` | NUMERIC(12,4) | % |
| `non_recurring_profit_yoy` | NUMERIC(12,4) | % |
| `eps` | NUMERIC(16,6) | |
| `roe` | NUMERIC(12,4) | % |
| `publish_date` | DATE | 披露日期 |
| `source_code` | VARCHAR(32) NOT NULL | |
| `fetch_time` | TIMESTAMP NOT NULL | |

约束：`UNIQUE (stock_id, report_period, source_code)`

### 3.5 `stock_dividends` 分红记录

| 字段 | 类型 |
|---|---|
| `id` | BIGSERIAL PK |
| `stock_id` | BIGINT NOT NULL |
| `fiscal_year` | VARCHAR(16) |
| `cash_dividend_per_share` | NUMERIC(20,6) | **每股现金分红（税前）** |
| `announcement_date` | DATE |
| `record_date` | DATE | 股权登记日 |
| `ex_dividend_date` | DATE | 除息日 |
| `payment_date` | DATE | 派息日 |
| `plan_status` | VARCHAR(32) | 实施/预案/取消 |
| `source_code` | VARCHAR(32) NOT NULL |

约束：`UNIQUE (stock_id, fiscal_year, ex_dividend_date, source_code)`

### 3.6 `stock_metric_values` 多源指标值

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `stock_id` | BIGINT NOT NULL | |
| `metric_code` | VARCHAR(64) NOT NULL | |
| `source_code` | VARCHAR(32) NOT NULL | |
| `raw_value` | VARCHAR(128) | 原始字符串，保留原精度 |
| `normalized_value` | NUMERIC(24,6) | 标准化值 |
| `unit` | VARCHAR(16) | |
| `data_time` | TIMESTAMP | |
| `fetch_time` | TIMESTAMP NOT NULL | |
| `status` | VARCHAR(24) NOT NULL | `SUCCESS`/`MISSING`/`TIMEOUT`/`ERROR`/`LOSS`/`NOT_READY`/`PROVISIONAL` |
| `raw_payload` | TEXT | 原始报文片段 |
| `calculation_version` | VARCHAR(32) | |

约束：`UNIQUE (stock_id, metric_code, source_code, data_time)`

### 3.7 `stock_daily_metrics` 每日指标快照

| 字段 | 类型 |
|---|---|
| `id` | BIGSERIAL PK |
| `stock_id` | BIGINT NOT NULL |
| `metric_code` | VARCHAR(64) NOT NULL |
| `metric_value` | NUMERIC(24,6) |
| `trade_date` | DATE NOT NULL |
| `source_code` | VARCHAR(32) NOT NULL |
| `calculation_version` | VARCHAR(32) NOT NULL |
| `created_at` | TIMESTAMP NOT NULL DEFAULT now() |

约束：`UNIQUE (stock_id, metric_code, trade_date, source_code)`

### 3.8 `metric_definitions` 指标定义

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `metric_code` | VARCHAR(64) NOT NULL UNIQUE | |
| `metric_name` | VARCHAR(64) NOT NULL | |
| `metric_category` | VARCHAR(32) NOT NULL | `base`/`quote`/`valuation`/`dividend`/`financial`/`technical` |
| `data_type` | VARCHAR(16) NOT NULL | `STRING`/`DECIMAL`/`PERCENT`/`BOOLEAN`/`DATE` |
| `unit` | VARCHAR(16) | |
| `description` | VARCHAR(512) | |
| `calculation_type` | VARCHAR(16) NOT NULL | `SOURCE`/`CALCULATED`/`PENDING` |
| `calculation_expression` | VARCHAR(512) | 公式说明（人可读） |
| `calculation_status` | VARCHAR(16) NOT NULL | `READY`/`PENDING`/`MISSING_DATA` |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE | |
| `default_precision` | INTEGER NOT NULL DEFAULT 2 | |
| `sort_order` | INTEGER NOT NULL DEFAULT 0 | |
| `created_at` / `updated_at` | TIMESTAMP | |

### 3.9 `metric_source_bindings` 指标×数据源绑定

| 字段 | 类型 |
|---|---|
| `id` | BIGSERIAL PK |
| `metric_code` | VARCHAR(64) NOT NULL |
| `provider_code` | VARCHAR(32) NOT NULL |
| `priority` | INTEGER NOT NULL DEFAULT 100 |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE |
| `is_primary` | BOOLEAN NOT NULL DEFAULT FALSE |
| `timeout_ms` | INTEGER DEFAULT 5000 |
| `cache_seconds` | INTEGER DEFAULT 60 |
| `created_at` / `updated_at` | TIMESTAMP |

约束：`UNIQUE (metric_code, provider_code)`

### 3.10 `data_providers` 数据源定义

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `provider_code` | VARCHAR(32) NOT NULL UNIQUE | |
| `provider_name` | VARCHAR(64) NOT NULL | |
| `provider_type` | VARCHAR(16) NOT NULL | `HTTP`/`PYTHON`/`SDK` |
| `base_url` | VARCHAR(256) | |
| `enabled` | BOOLEAN NOT NULL DEFAULT FALSE | |
| `requires_token` | BOOLEAN NOT NULL DEFAULT FALSE | |
| `token_encrypted` | VARCHAR(512) | **仅服务端使用，API 永不返回** |
| `request_interval_ms` | INTEGER NOT NULL DEFAULT 500 | |
| `rate_limit_config` | VARCHAR(256) | |
| `health_status` | VARCHAR(16) | `UP`/`DOWN`/`UNKNOWN` |
| `last_success_at` / `last_failure_at` | TIMESTAMP | |
| `description` | VARCHAR(512) | |
| `verified` | BOOLEAN NOT NULL DEFAULT FALSE | 是否经过真实接口测试 |
| `sort_order` | INTEGER NOT NULL DEFAULT 0 | |

### 3.11 `user_watchlists` 自选股

| 字段 | 类型 |
|---|---|
| `id` | BIGSERIAL PK |
| `user_id` | VARCHAR(64) NOT NULL DEFAULT 'local-user' |
| `stock_id` | BIGINT NOT NULL |
| `sort_order` | INTEGER NOT NULL DEFAULT 0 |
| `remark` | VARCHAR(200) |
| `created_at` | TIMESTAMP NOT NULL DEFAULT now() |

约束：`UNIQUE (user_id, stock_id)`

### 3.12 `user_metric_preferences` 用户指标偏好

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | |
| `user_id` | VARCHAR(64) NOT NULL DEFAULT 'local-user' | |
| `metric_code` | VARCHAR(64) NOT NULL | |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE | |
| `show_in_home` | BOOLEAN NOT NULL DEFAULT FALSE | |
| `show_in_watchlist` | BOOLEAN NOT NULL DEFAULT TRUE | |
| `show_in_detail` | BOOLEAN NOT NULL DEFAULT TRUE | |
| `sortable` | BOOLEAN NOT NULL DEFAULT FALSE | 参与排序 |
| `filterable` | BOOLEAN NOT NULL DEFAULT FALSE | 参与筛选 |
| `record_history` | BOOLEAN NOT NULL DEFAULT FALSE | 记录历史 |
| `sort_order` | INTEGER NOT NULL DEFAULT 0 | |
| `selected_source` | VARCHAR(32) | |
| `precision` | INTEGER | |
| `display_name` | VARCHAR(64) | 用户自定义显示名 |
| `unit_override` | VARCHAR(16) | |

约束：`UNIQUE (user_id, metric_code)`

### 3.13 `data_sync_logs` 采集日志

| 字段 | 类型 |
|---|---|
| `id` | BIGSERIAL PK |
| `provider_code` | VARCHAR(32) NOT NULL |
| `metric_code` | VARCHAR(64) |
| `sync_type` | VARCHAR(32) NOT NULL |
| `started_at` | TIMESTAMP NOT NULL |
| `finished_at` | TIMESTAMP |
| `total_count` / `success_count` / `failed_count` | INTEGER DEFAULT 0 |
| `sync_status` | VARCHAR(16) NOT NULL |
| `error_message` | VARCHAR(1024) |

索引：`idx_sync_logs_provider_time (provider_code, started_at DESC)`

### 3.14 `user_settings` 个人配置

| 字段 | 类型 |
|---|---|
| `id` | BIGSERIAL PK |
| `user_id` | VARCHAR(64) NOT NULL DEFAULT 'local-user' |
| `setting_key` | VARCHAR(64) NOT NULL |
| `setting_value` | VARCHAR(1024) |
| `created_at` / `updated_at` | TIMESTAMP |

约束：`UNIQUE (user_id, setting_key)`

### 3.15 `lingkan_schema_version` 迁移版本记录

| 字段 | 类型 |
|---|---|
| `version` | VARCHAR(64) PK |
| `script` | VARCHAR(256) |
| `applied_at` | TIMESTAMP NOT NULL DEFAULT now() |
| `success` | BOOLEAN NOT NULL |

> 该表由内置迁移器 `DbMigrationRunner` 维护，用于替代 Flyway 的版本管理职责。**不使用 Flyway 的原因见技术选型报告 R1。**

## 四、openGauss 兼容性说明

| 语法 | 是否可用 | 说明 |
|---|---|---|
| `BIGSERIAL` | ✅ | openGauss 支持 `SERIAL`/`BIGSERIAL` |
| `NUMERIC(p,s)` | ✅ | |
| `BOOLEAN` | ✅ | |
| `DEFAULT CURRENT_TIMESTAMP` | ✅ | 项目中统一写 `now()` 亦可 |
| `CREATE INDEX IF NOT EXISTS` | ✅ | openGauss 支持 |
| `LIMIT n` | ✅ | |
| `TEXT` | ✅ | |
| `ON CONFLICT DO NOTHING` | ⚠️ | openGauss 部分版本支持，**迁移脚本中避免使用**，改用先查后插 |
| `JSONB` | ⚠️ | 支持但生态工具差异大，原始报文用 `TEXT` |
| 序列自增 | ✅ | |

## 五、未来扩展预留

- 所有用户相关表已有 `user_id`，可直接支持多用户。
- `metric_definitions` 已支持用户自定义指标（`calculation_expression` 字段预留）。
- `data_providers` 已支持新增数据源而不改代码。
- 若要支持港股/美股，`exchange` 扩展枚举即可，但需重建唯一索引语义（交易所+代码仍唯一）。
