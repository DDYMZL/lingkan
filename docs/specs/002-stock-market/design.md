# Spec 002 — 设计

## 1. 后端

### 1.1 调用链

```
StockController.list
  └─ StockService.page
       ├─ 参数校验（exchange 白名单、metricCode 白名单、size clamp）
       ├─ MyBatis-Plus 分页查询 stocks
       ├─ MetricValueService.resolveMetricsBatch(stocks, userId)
       │    ├─ QuoteSnapshotService.getQuotes(codes)   // 1 次批量上游请求
       │    └─ 逐指标解析：PENDING → 计算类 → 行情类 → 指标值表
       └─ 组装 PageResult<StockRowDto>
```

### 1.2 排序实现

MyBatis-Plus 的 `orderBy` 无法直接排序"关联子查询"，因此使用受控的 `last()` 追加：

```sql
ORDER BY (SELECT v.normalized_value FROM stock_metric_values v
          WHERE v.stock_id = stocks.id AND v.metric_code = ?
          ORDER BY v.fetch_time DESC LIMIT 1) DESC NULLS LAST
```

指标编码经正则白名单校验后才拼入，避免注入。

### 1.3 筛选实现

每个条件翻译为 `EXISTS` 子查询：

```sql
EXISTS (SELECT 1 FROM stock_metric_values v
        WHERE v.stock_id = stocks.id AND v.metric_code = ? AND v.normalized_value >= ?)
```

### 1.4 已知限制

- 排序/筛选仅作用于 `stock_metric_values`（落库指标），**不含实时行情字段**。
  原因：实时行情不落库到指标值表，避免高频写库。Phase 4 引入 `stock_quotes` 物化后，
  可扩展为"行情字段走 stock_quotes、其余走指标值表"的联合排序。

## 2. 前端

| 组件 | 职责 |
|---|---|
| `StockSearch.vue` | 输入防抖 300ms，调 `/stocks/search`，下拉选择 |
| `MetricSort.vue` | 横向滚动指标 chips，点击同一指标切换升降序 |
| `MetricFilter.vue` | 按"可筛选"指标生成数值区间输入，提交时拼 `>=a&<=b` |
| `StockRow.vue` | 紧凑行：名称/代码 + 动态指标网格 + 星标 |
| `LkEmpty.vue` | 空态，明确提示"后端未返回行情数据时不展示任何估算值" |

页面状态由 `useStockStore` 管理，`marketList` 支持追加模式（`append=true`）实现上拉加载。

## 3. 交互细节

- 下拉刷新重置到第 1 页；上拉到底触发下一页；`finished = 已加载数 >= total`。
- 加自选后本地即时更新星标，失败回滚并 toast。
- 点击指标数值 → 跳转数据源对比页（`/pages/metric-compare`）。
