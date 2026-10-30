# 009-全市场股票同步 · 任务清单

> 版本：v1.0　日期：2026-10-30（实现完成后补记）

- [x] T9-01 股票清单适配器：StockListProvider 接口 + EastMoneyStockListProvider（主）+ SinaStockListProvider（备）
- [x] T9-02 批量指标适配器：BatchStockDataProvider 接口，EastMoneyProvider 实现一次请求多指标
- [x] T9-03 V4 迁移：stock_sync_task / stock_sync_task_detail
- [x] T9-04 StockSyncExecutor：清单 → 行情+腾讯指标 → 东财指标 → 失败重试
- [x] T9-05 StockSyncScheduler：04:00 cron + 开关
- [x] T9-06 StockSyncLockService：锁超时自动释放
- [x] T9-07 StockSyncController：full / incremental / single / tasks / failures / retry
- [x] T9-08 配置：lingkan.stock-sync.* 与 application.yml
- [ ] T9-09 首次全量同步执行验证（依赖 openGauss + 网络）
- [ ] T9-10 每日快照任务实测
