# 007-历史数据与技术指标 · 开发任务

> 版本：v1.0

| 编号 | 任务 | 状态 |
|---|---|---|
| T7-01 | `stock_daily_quotes` / `stock_daily_metrics` 建表 + 唯一约束 + 索引 | ✅ |
| T7-02 | `StockDailyQuote` / `StockDailyMetric` 实体与 Mapper | ✅ |
| T7-03 | `MetricCalculator` 接口 + `CalcContext` | ✅ |
| T7-04 | `NewLow60d3dCalculator` | ✅ |
| T7-05 | 60 日高低 / N 日涨跌幅计算器 | ✅ |
| T7-06 | `CalcVersion` 版本常量管理 | ✅ |
| T7-07 | `HistoryService.series()` / `batch()` | ✅ |
| T7-08 | `GET /stocks/{code}/history` 与 `/history/batch` | ✅ |
| T7-09 | 收盘快照任务骨架 | ✅（默认关闭） |
| T7-10 | 与东方财富 K 线接口对接写入 `stock_daily_quotes` | ⏳ 待联调 |
| T7-11 | `new_low_60d_3d` 边界单测（数据不足 / 恰等于 / 当日临时） | ⏳ 待补 |
| T7-12 | 历史快照积累与查询验证 | ⏳ 阻塞于数据采集 |
| T7-13 | 周30/周中/周60/周下/周上/带内位置 | ⏸ **阻塞于用户确认公式（Q7-1 ~ Q7-8）** |
| T7-14 | 前复权支持 | ⏸ 待用户确认（Q7-10） |
| T7-15 | 历史口径覆盖策略确认 | ⏸ 待用户确认（Q7-9） |
