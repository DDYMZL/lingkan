# 009-全市场股票同步 · 技术设计

> 版本：v1.0　日期：2026-10-30

## 一、模块结构

```text
com.lingkan.sync
├── StockSyncProperties        # lingkan.stock-sync.* 配置绑定
├── controller/StockSyncController   # /api/v1/stocks/sync
├── scheduler/StockSyncScheduler     # 04:00 cron（可关闭）
├── service/
│   ├── StockListService       # 清单拉取（东财主/新浪备）+ 校验
│   ├── StockSyncExecutor      # 执行器：清单→行情→东财指标→失败重试
│   ├── StockSyncService       # 任务编排与查询
│   └── StockSyncLockService   # 任务锁（锁超时自动释放）
├── entity/ StockSyncTask / StockSyncTaskDetail
├── mapper/ StockSyncTaskMapper / StockSyncTaskDetailMapper / StockSyncLockMapper
├── dto/  StockSyncRequest / StockSyncTaskVo / StockSyncDetailVo / SyncSubmitVo / StockBasicDto
└── support/StockCodeRules     # 6 位代码与交易所前缀规则
```

## 二、数据库（V4 迁移）

- `stock_sync_task`：任务主表（task_no 唯一、sync_type、状态、进度、各维度计数、耗时、错误信息）。
- `stock_sync_task_detail`：明细表（stock_code/provider_code/metric_code/status/retry_count/error_message），支撑失败追溯与精准重试。

## 三、同步流程

```text
FULL：清单全量同步 → 行情+腾讯指标 → 东财指标（全部绑定）
INCREMENTAL：跳过清单，行情+东财指标（新入库股票）
RETRY：仅重试失败明细涉及的指标（metric_filter 传回）
```

- 行情批量：腾讯一次 100 只（`lingkan.stock-sync.quote.batch-size`），GBK 解码。
- 东财指标：单股快照一次返回全部绑定指标，有界并发 6，150ms 间隔。
- 落库：stock_quotes（最新快照）+ stock_metric_values（指标值）+ stock_daily_metrics（行情类指标每日留痕）。
- 东财熔断：连续 30 次失败熔断 3 分钟，每轮同步开始前复位探测。

## 四、接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/stocks/sync/full` | 全量同步（异步，返回 taskId） |
| POST | `/api/v1/stocks/sync/incremental` | 增量同步 |
| POST | `/api/v1/stocks/sync/single` | 单只股票同步 |
| GET | `/api/v1/stocks/sync/tasks` | 任务分页 |
| GET | `/api/v1/stocks/sync/tasks/{id}` | 任务详情 |
| GET | `/api/v1/stocks/sync/tasks/{id}/failures` | 失败明细 |
| POST | `/api/v1/stocks/sync/tasks/{id}/retry` | 失败重试 |

## 五、配置要点（application.yml）

```yaml
lingkan:
  stock-sync:
    enabled: true            # 定时任务开关（手动接口不受影响）
    cron: "0 0 4 * * ?"      # 每天凌晨 04:00
    auto-initialize: false   # 首次初始化手动调用，禁止启动自动 FULL
    stock-list: { provider: eastmoney, fallback: sina, min-stock-count: 3000 }
    quote: { enabled: true, provider: tencent, batch-size: 100 }
    eastmoney: { enabled: true, concurrency: 6, request-interval-ms: 150 }
    task: { lock-timeout-minutes: 120 }
```

## 六、已知限制

- 分红/财报同步不在本模块范围（见 spec 010）。
- 定时任务不重试历史失败（由手动 retry 接口处理）。
