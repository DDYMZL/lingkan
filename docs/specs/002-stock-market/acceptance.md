# Spec 002 — 验收标准

| 编号 | 验收项 | 判定方法 |
|---|---|---|
| 002-1 | 列表分页正确 | 请求 `size=20&page=2`，返回第 21–40 条，`total` 与库内一致 |
| 002-2 | 搜索可用 | `keyword=茅台` 返回 600519 贵州茅台；`keyword=600519` 同样命中 |
| 002-3 | 交易所筛选生效 | `exchange=SH` 结果全部为沪市代码 |
| 002-4 | 指标排序生效 | `sortMetric=dividend_yield_ttm&sortOrder=desc`，首个结果该指标最大 |
| 002-5 | 指标筛选生效 | `metricFilters={"dividend_yield_ttm":">=4"}` 结果均 ≥4 |
| 002-6 | 字段动态化 | 关闭某指标后，返回的 `metrics` 中不再包含该 code |
| 002-7 | 无数据不虚构 | 无行情时 `status=MISSING` 且 `displayValue="--"`，无 0 值 |
| 002-8 | 非法排序参数被忽略 | `sortMetric=1;drop table` 不报错、返回默认排序 |
| 002-9 | 上拉加载不重复 | 连续加载 3 页，无重复 `stockCode` |
| 002-10 | 加自选即时生效 | 点击星标后自选页可见该股票 |
