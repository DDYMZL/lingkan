# Spec 003 — 任务

## 已完成

- [x] `metric_definitions` / `metric_source_bindings` / `user_metric_preferences` / `stock_metric_values` / `stock_daily_metrics` 表结构
- [x] V2 迁移：74 项指标种子数据（含 PENDING 标记与待确认问题）
- [x] `MetricDefinitionService`（列表、分类聚合、单指标，Caffeine 缓存）
- [x] `MetricPreferenceService`（默认初始化、整量保存、重置、与定义合并）
- [x] `MetricValueService`（五层取值编排、PE-TTM、股息率 TTM、格式化）
- [x] `DecimalUtils` + `DecimalUtilsTest`（15 个用例）
- [x] `MetricController` / `UserMetricPreferenceController`
- [x] 前端指标配置页、`MetricValue` 组件、metric store

## 待办

- [ ] PE-TTM：总股本缺失时用"股价×总股本"补算（当前依赖行情总市值）
- [ ] 股息率：分红方案状态精细判定（预案/股东大会通过/已实施/取消）
- [ ] 股息率：除权除息价格还原（送转后的历史股价调整）
- [ ] 营收同比、净利润同比、股利支付率接入实际财务数据后补测试
- [ ] 指标精度/单位自定义的联动校验（如 PERCENT 类型不允许改单位）
- [ ] `!` 周线指标与 60 日新低：等待 Q1–Q4 口径确认
