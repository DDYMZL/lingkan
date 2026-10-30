# Spec 002 — 任务

- [x] `stocks` 表结构与索引
- [x] `StockMapper` / `StockService.page` / `StockService.search`
- [x] 指标白名单校验与排序子查询
- [x] 指标区间筛选（EXISTS 子查询）
- [x] `GET /api/v1/stocks`、`GET /api/v1/stocks/search`
- [x] 前端市场页（搜索 + 交易所筛选 + 排序 + 筛选 + 加载更多）
- [x] 前端 `StockRow` 组件（动态指标渲染）
- [ ] 全市场股票基础信息灌入脚本（东财 clist 分页）
- [ ] 行情字段参与排序（待 `stock_quotes` 物化后）
- [ ] ST 过滤开关
- [ ] 真机联调（列表滚动性能、加载更多时机）
