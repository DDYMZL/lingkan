# Spec 003 — 设计

## 1. 数据模型

```
metric_definitions (指标库)
        ▲
        │ metric_code
metric_source_bindings (指标 ↔ 数据源，多对多，含优先级/主源)
        │
        ▼
data_providers (数据源)

user_metric_preferences (用户偏好，user_id + metric_code 唯一)
        │
        ▼
stock_metric_values (指标值：stock_id + metric_code + source_code 唯一)
stock_daily_metrics (每日快照：stock_id + metric_code + trade_date + source_code 唯一)
```

## 2. 取值编排（MetricValueService）

优先级严格如下，逐层降级：

```
1. calculation_status == PENDING  → PENDING（附待确认原因），不输出数值
2. calculation_type == CALCULATED → 按公式计算（数据不足 → MISSING + 明确原因）
3. 行情快照可提供该指标          → OK（source = 实际提供方，附 dataTime/fetchTime）
4. stock_metric_values 有记录     → OK（source = 用户指定源，否则取最新）
5. 都没有                        → MISSING
```

返回值统一为 `MetricValueDto`：

```json
{
  "metricCode": "dividend_yield_ttm",
  "metricName": "股息率 TTM",
  "unit": "%",
  "precision": 2,
  "value": 3.85,
  "displayValue": "3.85",
  "status": "OK",
  "sourceCode": "system_calc",
  "sourceName": "系统计算",
  "dataTime": "2026-10-30T00:00:00",
  "fetchTime": "2026-10-30T15:20:11",
  "calculationVersion": "dividend_yield_ttm@v1"
}
```

## 3. 计算实现要点

- 全部使用 `BigDecimal` + `MathContext(16, HALF_UP)`，禁止 double 参与金额运算。
- `DecimalUtils.yoy/percent/divide/bandPosition` 在**除数为 0 或数据缺失时返回 null**，不抛异常、不返回 Infinity。
- 展示格式化统一走 `MetricValueService.format(value, precision)`：null → `"--"`。
- 周线数据不足（< N 周）→ 返回 MISSING，而不是用不足窗口强行计算。

## 4. 用户偏好初始化

首次访问 `GET /user/metric-preferences` 时若为空，按指标库自动生成：

- 默认启用：`stock_name`、`latest_price`、`change_percent`、`pe_ttm`、`dividend_yield_ttm`、`net_profit_parent_yoy`（均为 READY）。
- 默认展示位置：首页 + 自选 + 详情。
- 默认不可筛选、可排序；`pe_ttm`/`dividend_yield_ttm`/`latest_price` 默认记录历史。
- PENDING 指标默认**不启用**。

## 5. 接口

```
GET  /api/v1/metrics
GET  /api/v1/metrics/categories
GET  /api/v1/metrics/{metricCode}
GET  /api/v1/metrics/{metricCode}/sources
PUT  /api/v1/metrics/{metricCode}/sources      # 切换主数据源
GET  /api/v1/user/metric-preferences
PUT  /api/v1/user/metric-preferences           # 整量保存
POST /api/v1/user/metric-preferences/reset
```

## 6. 前端

- `stores/metric.ts`：定义 + 偏好合并，派生 `homeMetrics / watchlistMetrics / detailMetrics`。
- `components/metric/MetricValue.vue`：**唯一**指标展示组件，所有指标复用；展示值、单位、状态标签、来源。
- `pages/metric-config`：分类列表，支持启用、排序（↑↓）、精度、单位、数据源 picker、三个展示位置开关、筛选/排序/历史开关。

## 7. 测试

`DecimalUtilsTest` 覆盖：正常同比、上年同期为负、上年同期为 0、财报未披露、PE 正常/零利润/负利润、多次分红、未实施分红、股价为 0、带内位置区间与零区间、非法数值解析、四舍五入。
