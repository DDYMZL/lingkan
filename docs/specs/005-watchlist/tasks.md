# 005-自选股 · 开发任务

> 版本：v1.0

| 编号 | 任务 | 状态 |
|---|---|---|
| T5-01 | `UserWatchlist` 实体 + Mapper | ✅ |
| T5-02 | V1 迁移建 `user_watchlists` 表与唯一约束 | ✅ |
| T5-03 | `WatchlistService`：list/add/remove/sort/remark/refresh | ✅ |
| T5-04 | `WatchlistController`：6 个端点 | ✅ |
| T5-05 | 指标填充复用 `MetricValueService` | ✅ |
| T5-06 | 前端 `pages/watchlist/index.vue` | ✅ |
| T5-07 | 前端拖动排序交互 | ✅（基础版，使用上移/下移按钮 + 长按排序占位） |
| T5-08 | 异步刷新线程池 | ✅（`AsyncConfig`） |
| T5-09 | 添加自选幂等测试 | ⏳ 待补单测 |
| T5-10 | 排序并发覆盖问题处理 | ⏸ 第一版不处理 |
| T5-11 | 自选分组 | ⏸ 未纳入范围 |
