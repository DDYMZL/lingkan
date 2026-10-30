# LingKan API 契约

> 版本：v1.0
> 前缀：`/api/v1`
> 日期：2026-10-30
> 状态：`implemented`（代码已落地，运行时验证待数据库连通）

## 一、统一约定

### 1.1 统一响应体

```json
{
  "code": 0,
  "message": "ok",
  "data": { },
  "traceId": "a1b2c3d4",
  "timestamp": "2026-10-30T15:20:00"
}
```

| 字段 | 说明 |
|---|---|
| `code` | 0 表示成功；非 0 为业务错误码 |
| `message` | 人类可读信息 |
| `data` | 业务数据，错误时为 `null` |
| `traceId` | 请求追踪 ID，与日志关联 |
| `timestamp` | 服务端时间 |

### 1.2 分页响应

```json
{
  "code": 0,
  "data": {
    "list": [],
    "total": 5234,
    "page": 1,
    "pageSize": 20,
    "hasNext": true
  }
}
```

### 1.3 错误码

| code | 含义 |
|---|---|
| 0 | 成功 |
| 40001 | 参数校验失败 |
| 40004 | 资源不存在（股票/指标/数据源） |
| 40009 | 数据源不可用 |
| 40010 | 数据尚未采集（MISSING） |
| 40029 | 计算未就绪（公式待确认） |
| 50000 | 服务内部错误 |

### 1.4 用户标识

第一版为单用户。前后端约定：
- 请求头 `X-User-Id`（可选），缺省为 `local-user`。
- 后端 `UserContext` 从拦截器取，写入 ThreadLocal。
- **不做登录鉴权**。

### 1.5 时间与数值

- 所有时间 ISO-8601，含时区偏移，如 `2026-10-30T15:16:55+08:00`。
- 所有金融数值为 JSON number，保留服务端精度；展示精度由前端指标配置决定。
- 单位通过独立字段返回，不拼接在数值里。

---

## 二、股票

### 2.1 股票列表

```http
GET /api/v1/stocks
```

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` | int | 否 | 默认 1 |
| `pageSize` | int | 否 | 默认 20，上限 100 |
| `keyword` | string | 否 | 代码或名称模糊 |
| `exchange` | string | 否 | `SH` / `SZ` / `BJ` |
| `sortBy` | string | 否 | 指标代码，如 `dividend_yield_ttm` |
| `sortOrder` | string | 否 | `asc` / `desc` |
| `filters` | string | 否 | JSON：`[{"metric":"pe_ttm","op":"lt","value":20}]` |

响应 `data.list[]` 为 `StockRowDto`：

```json
{
  "stockCode": "600519",
  "stockName": "贵州茅台",
  "exchange": "SH",
  "metrics": {
    "latest_price": { "value": 1392.05, "display": "1392.05", "unit": "元",
                      "source": "tencent", "status": "SUCCESS",
                      "dataTime": "2026-10-30T15:16:55", "fetchTime": "2026-10-30T15:17:02" },
    "change_percent": { "value": 2.81, "display": "+2.81%", "unit": "%", "source": "tencent",
                        "status": "SUCCESS", "dataTime": "2026-10-30T15:16:55" }
  }
}
```

> `metrics` 的键集合完全由用户指标配置决定，后端不固定字段。这是「指标配置化」的核心体现。

### 2.2 搜索

```http
GET /api/v1/stocks/search?keyword=茅台&limit=10
```

### 2.3 股票详情

```http
GET /api/v1/stocks/{stockCode}
```

返回 `StockDetailDto`：基础信息 + `metrics`（全部启用指标）+ `dividends`。

### 2.4 实时行情

```http
GET /api/v1/stocks/{stockCode}/quote
```

返回原始行情字段（不经指标配置过滤），含 `source` / `dataTime` / `fetchTime`。

### 2.5 股票的指标集合

```http
GET /api/v1/stocks/{stockCode}/metrics
```

### 2.6 历史序列

```http
GET /api/v1/stocks/{stockCode}/history?metricCode=pe_ttm&period=1y&sourceCode=eastmoney
```

| 参数 | 说明 |
|---|---|
| `metricCode` | 指标代码 |
| `period` | `1y` / `3y` / `5y` / `all` |
| `startDate` / `endDate` | 自定义区间（优先于 period） |
| `sourceCode` | 指定来源 |

响应 `HistorySeriesDto`：

```json
{
  "stockCode": "600519",
  "metricCode": "pe_ttm",
  "sourceCode": "calculated",
  "unit": "倍",
  "points": [
    { "date": "2026-10-28", "value": 19.42 },
    { "date": "2026-10-29", "value": 19.55 }
  ]
}
```

### 2.7 批量历史

```http
GET /api/v1/stocks/{stockCode}/history/batch?metricCodes=pe_ttm,dividend_yield_ttm&period=1y
```

### 2.8 分红记录

```http
GET /api/v1/stocks/{stockCode}/dividends
```

### 2.9 多源对比

```http
GET /api/v1/stocks/{stockCode}/metrics/{metricCode}/sources
```

响应见 `docs/specs/004-data-source/design.md` 第六节。**不返回平均值字段**。

### 2.10 手动刷新

```http
POST /api/v1/stocks/{stockCode}/refresh
```

采用异步提交，立即返回 `202` 语义的 `taskId`，不阻塞小程序请求。

---

## 三、指标

### 3.1 指标列表

```http
GET /api/v1/metrics?category=valuation&enabled=true
```

返回 `MetricDefinitionDto[]`：

```json
{
  "metricCode": "pe_ttm",
  "metricName": "市盈率 TTM",
  "metricCategory": "valuation",
  "dataType": "DECIMAL",
  "unit": "倍",
  "enabled": true,
  "calculationType": "CALCULATED",
  "calculationStatus": "READY",
  "calculationExpression": "total_market_cap / sum(net_profit_parent, 4Q)",
  "defaultPrecision": 2,
  "description": "总市值 ÷ 最近四个已披露季度归母净利润之和"
}
```

> `calculationStatus` 取值：`READY` / `PENDING` / `MISSING_DATA`。`PENDING` 的指标前端必须显示「待配置计算公式」且不可启用计算。

### 3.2 指标分类

```http
GET /api/v1/metrics/categories
```

返回分组树，用于指标配置页面按分类渲染。

### 3.3 指标详情

```http
GET /api/v1/metrics/{metricCode}
```

### 3.4 指标的数据源绑定

```http
GET /api/v1/metrics/{metricCode}/sources      # 查询绑定
PUT /api/v1/metrics/{metricCode}/sources      # 更新优先级 / 主源
```

请求体：

```json
[
  { "providerCode": "tencent", "priority": 1, "isPrimary": true, "enabled": true },
  { "providerCode": "eastmoney", "priority": 2, "isPrimary": false, "enabled": true }
]
```

约束：同一 `metricCode` 下最多一个 `isPrimary=true`。

### 3.5 用户指标偏好

```http
GET  /api/v1/user/metric-preferences
PUT  /api/v1/user/metric-preferences
POST /api/v1/user/metric-preferences/reset
```

`PUT` 请求体为整份偏好列表（全量覆盖）：

```json
[
  { "metricCode": "latest_price", "enabled": true,
    "showInHome": true, "showInWatchlist": true, "showInDetail": true,
    "sortOrder": 1, "selectedSource": "tencent", "precision": 2 }
]
```

---

## 四、数据源

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/data-sources` | 数据源列表 |
| GET | `/api/v1/data-sources/bindings` | 全部指标-数据源绑定 |
| GET | `/api/v1/data-sources/{providerCode}` | 数据源详情 |
| POST | `/api/v1/data-sources/{providerCode}/test` | **真实**连通性测试 |
| POST | `/api/v1/data-sources/{providerCode}/enable` | 启用 |
| POST | `/api/v1/data-sources/{providerCode}/disable` | 停用 |
| GET | `/api/v1/data-sources/{providerCode}/logs?limit=50` | 采集日志 |

`ProviderDto` 字段：

```json
{
  "providerCode": "tencent",
  "providerName": "腾讯财经",
  "providerType": "HTTP",
  "baseUrl": "https://qt.gtimg.cn",
  "enabled": true,
  "requiresToken": false,
  "requestIntervalMs": 500,
  "healthStatus": "UP",
  "lastSuccessAt": "2026-10-30T15:17:02",
  "lastFailureAt": null,
  "supportedMetrics": ["latest_price", "change_percent"],
  "supportedMetricCount": 12,
  "description": "批量实时行情",
  "verified": true
}
```

> **安全**：响应中不存在 token 字段。前端只能写入，不能读取。

---

## 五、自选股

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/watchlist` | 自选列表（含动态指标） |
| POST | `/api/v1/watchlist` | 添加，body `{ "stockCode": "600519", "remark": "" }` |
| DELETE | `/api/v1/watchlist/{stockCode}` | 删除 |
| PUT | `/api/v1/watchlist/sort` | 排序，body `{ "stockCodes": ["600519","000001"] }` |
| PUT | `/api/v1/watchlist/{stockCode}/remark` | 改备注 |
| POST | `/api/v1/watchlist/refresh` | 批量刷新自选行情（异步） |

---

## 六、市场与系统

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/market/overview` | 上涨/下跌/平盘数量、更新时间 |
| POST | `/api/v1/market/refresh` | 触发全市场刷新（异步） |
| GET | `/api/v1/system/data-status` | 各数据源最后更新时间与健康状态 |

`MarketOverviewDto`：

```json
{
  "totalCount": 5234,
  "upCount": 2811,
  "downCount": 2109,
  "flatCount": 314,
  "dataTime": "2026-10-30T15:00:00",
  "fetchTime": "2026-10-30T15:17:10",
  "source": "eastmoney",
  "status": "SUCCESS"
}
```

> 若数据未采集，`status` 为 `MISSING`，全部计数为 `null`（**不是 0**），前端显示「暂无数据」而非「0 只上涨」。

---

## 七、接口与前端服务的对应

| 前端文件 | 覆盖接口 |
|---|---|
| `src/services/request.ts` | 统一封装、错误码映射、traceId 透传 |
| `src/services/stock.ts` | 2.1 ~ 2.5、2.9、2.10 |
| `src/services/market.ts` | 六 |
| `src/services/metrics.ts` | 三 |
| `src/services/datasource.ts` | 四 |
| `src/services/watchlist.ts` | 五 |
| `src/services/history.ts` | 2.6、2.7 |

## 八、尚未实现的接口（按原文档列出但延后）

| 接口 | 原因 |
|---|---|
| `POST /metrics` `PUT /metrics/{code}` `DELETE /metrics/{code}` | 指标定义为系统内置，第一版不允许用户增删指标定义，仅允许调整偏好。**如需支持自定义指标，需先补 Spec** |
| `POST /data-sources` `PUT /data-sources/{code}` | 第一版数据源为内置注册，不支持小程序侧新增；Token 写入接口待 Tushare 接入时补 |
