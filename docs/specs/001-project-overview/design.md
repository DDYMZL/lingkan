# Spec 001 — 设计

## 1. 系统形态

```
微信小程序（原生 WXML/WXSS/JS，无跨端框架）
        │  HTTPS  /api/v1
        ▼
      Nginx（反向代理，仅暴露 /api）
        │
        ▼
Spring Boot 后端（模块化单体）
        │                    │
        ▼                    ▼
   openGauss           第三方数据源
  （指标/行情/配置）   （东财/腾讯/新浪/…）
```

不使用微服务，不引入消息队列，不引入 Redis（第一版 Caffeine 本地缓存足够）。

## 2. 模块划分

| 包 | 职责 |
|---|---|
| `common` | 统一响应、异常、迁移执行器、缓存、异步、工具 |
| `stock` | 股票基础信息、列表、搜索、详情编排 |
| `market` | 市场概览、涨跌统计 |
| `metrics` | 指标库、用户偏好、指标取值编排、行情快照 |
| `datasource` | 数据源注册、适配器 SPI、健康检查、采集日志 |
| `dividend` | 分红记录 |
| `financial` | 财务数据 |
| `technical` | 技术指标计算（Phase 6，公式待确认） |
| `watchlist` | 自选股 |
| `history` | 历史指标序列 |
| `scheduler` | 定时采集（默认关闭） |
| `system` | 交易时段、数据状态 |

## 3. 关键设计决策

1. **指标值三层来源**：计算值 → 行情快照 → 指标值表。优先级写死在 `MetricValueService`，可测试。
2. **数据源以适配器隔离**：`StockDataProvider` 接口 + `ProviderRegistry`，Controller 不接触 HTTP 客户端。
3. **数据库迁移自研**：openGauss 对 Flyway 方言支持不确定，改用 120 行自研执行器（详见 ADR-0002）。
4. **PENDING 指标不参与取值**：`MetricValueService` 第一道判断就是计算状态，避免"编造公式"。

## 4. 数据流（以"市场列表"为例）

1. 小程序 `GET /api/v1/stocks?page=1&size=20&sortMetric=dividend_yield_ttm`。
2. `StockService` 校验指标编码白名单 → 拼装排序子查询 → 分页查询 `stocks`。
3. 拿到当页 20 只股票后，`MetricValueService.resolveMetricsBatch` 一次性批量取行情（1 次上游请求）。
4. 对每只股票逐个指标解析：PENDING 跳过 → 计算类走公式 → 行情类取快照 → 其余查 `stock_metric_values`。
5. 组装 `StockRowDto`（字段 Map 由用户偏好决定）返回。

## 5. 待补充设计

- Phase 6：技术指标计算引擎（滚动窗口、复权处理）
- Phase 4：全市场股票列表首次灌入与增量更新策略
- Phase 7：小程序 HTTPS 域名备案与部署
