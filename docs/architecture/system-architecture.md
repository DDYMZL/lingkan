# 系统架构

> 版本：v1.0
> 日期：2026-10-30

## 一、整体拓扑

```text
┌──────────────────────┐
│  微信小程序（前端）    │  原生小程序：WXML + WXSS + JS（无跨端框架）
│  frontend/           │  TabBar: 首页/市场/自选/我的
└──────────┬───────────┘
           │ HTTPS  /api/v1/*
           ▼
┌──────────────────────┐
│  Nginx（反向代理）     │  docker/nginx/default.conf
└──────────┬───────────┘
           ▼
┌──────────────────────────────────────────────────┐
│  Java 后端（模块化单体）  backend/                  │
│  Spring Boot 4.1.2 / Java 25 / MyBatis-Plus       │
│                                                   │
│  stock  market  metrics  datasource  dividend     │
│  financial  technical  watchlist  history         │
│  scheduler  system                                │
│                                                   │
│  ┌────────────────────────────────────────────┐  │
│  │ datasource/provider  统一适配器层            │  │
│  │  ├ TencentProvider      (HTTP, GBK)        │  │
│  │  ├ EastMoneyProvider    (HTTP, JSON)       │  │
│  │  ├ SinaProvider         (HTTP, 需 Referer)  │  │
│  │  └ PendingProvider      (占位，未启用)      │  │
│  └────────────────────────────────────────────┘  │
└──────────┬───────────────────────┬───────────────┘
           │ JDBC                  │ HTTP
           ▼                       ▼
┌──────────────────────┐   ┌──────────────────────────┐
│  openGauss 5.0.0     │   │ 外部公开数据源             │
│  持久化卷             │   │ qt.gtimg.cn / push2.eastmoney │
└──────────────────────┘   │ hq.sinajs.cn / cninfo ...  │
                           └──────────────────────────┘
        （可选，第一期不部署）
┌──────────────────────┐   ┌──────────────────────┐
│  Redis（预留）        │   │ Python AKShare 采集器 │
└──────────────────────┘   └──────────────────────┘
```

## 二、分层职责

| 层 | 职责 | 禁止 |
|---|---|---|
| Controller | 参数校验、调用 Service、返回统一响应 | 不得调用第三方接口、不得写业务逻辑 |
| Service | 业务编排、事务、跨模块调用 | 不得直接操作其他模块的 Mapper |
| Mapper | 单表 CRUD | 不得包含业务判断 |
| Provider | 外部数据获取与字段标准化 | 不得依赖业务模块 |

**跨模块规则**：模块间只通过 Service 接口交互。例如 `watchlist` 需要指标值时调用 `metrics.MetricValueService`，不得直接注入 `metrics.mapper.*`。

## 三、模块清单

| 模块 | 职责 | 对外 Service |
|---|---|---|
| `stock` | 股票基础信息、列表、搜索、详情 | `StockService` |
| `market` | 市场概览、刷新 | `MarketService` |
| `metrics` | 指标定义、指标偏好、指标值解析 | `MetricDefinitionService` / `MetricPreferenceService` / `MetricValueService` |
| `datasource` | 数据源注册、启停、健康、适配器、绑定 | `DataSourceService` / `ProviderRegistry` |
| `dividend` | 分红记录 | `StockDividendMapper`（暂无独立 Service） |
| `financial` | 财务数据 | `StockFinancialMapper` |
| `technical` | 技术指标计算器 | `TechnicalMetricService` |
| `watchlist` | 自选股 | `WatchlistService` |
| `history` | 历史序列查询 | `HistoryService` |
| `scheduler` | 定时采集与快照 | — |
| `system` | 数据状态、运行信息 | `SystemService` |
| `common` | 响应包装、异常、配置、工具 | — |

## 四、关键横切机制

### 4.1 统一响应

`ApiResponse<T>` + `GlobalExceptionHandler`：所有异常转为 `{code, message, data:null, traceId}`。

### 4.2 请求追踪

`RequestTraceFilter` 生成 `traceId` 写入 MDC 与响应体，日志格式包含 `traceId`，便于把小程序一次失败请求与后端日志对上。

### 4.3 用户上下文

`UserContextInterceptor` 从 `X-User-Id` 读取，缺省 `local-user`，写入 ThreadLocal。第一版不做鉴权。

### 4.4 缓存

Caffeine，按指标类型设置 TTL：
- 行情 60s
- 估值 300s
- 分红 3600s
- 指标定义 永久（启动加载）

### 4.5 异步

`AsyncConfig` 提供专用线程池（核心 4 / 最大 8 / 队列 200），用于刷新任务。刷新接口立即返回，避免小程序请求长时间阻塞。

### 4.6 数据可追溯

三处强制：
1. 每个指标值带 `source_code` + `data_time` + `fetch_time`。
2. `stock_metric_values.raw_payload` 保存原始报文片段。
3. 计算类指标带 `calculation_version`。

## 五、部署形态

单机 Docker Compose（openGauss + backend + nginx），面向个人使用，不追求高可用。

| 容器 | 端口 | 健康检查 |
|---|---|---|
| opengauss | 5432 | `gsql -c "select 1"` |
| backend | 8080 | `/actuator/health` |
| nginx | 80 | `wget -q -O- localhost/healthz` |

## 六、演进方向（不在第一版范围）

- 多用户 + 登录鉴权
- Redis 缓存层替换 Caffeine
- Python 采集器容器（AKShare / BaoStock）
- 财报 PDF 解析（巨潮）
- 指标计算 DSL（让用户自定义公式）
