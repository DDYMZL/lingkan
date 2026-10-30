# 005-自选股 · 技术设计

> 版本：v1.0
> 日期：2026-10-30

## 一、数据模型

```sql
CREATE TABLE user_watchlists (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL DEFAULT 'local-user',
    stock_id    BIGINT       NOT NULL,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    remark      VARCHAR(200),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_watchlist_user_stock UNIQUE (user_id, stock_id)
);
```

`stock_id` 外键指向 `stocks.id`，但**不加数据库外键约束**（openGauss 上跨模块外键会限制批量导入性能），改由应用层保证。

## 二、Service 设计

`WatchlistService`：

| 方法 | 说明 |
|---|---|
| `list(userId)` | 联表 `user_watchlists` + `stocks`，按 `sort_order` 升序；再逐只填充指标 |
| `add(userId, stockCode, remark)` | 校验股票存在 → 查重 → 取当前最大 `sort_order + 1` 插入 |
| `remove(userId, stockCode)` | 按 `(user_id, stock_id)` 删除 |
| `sort(userId, stockCodes)` | 事务内按入参顺序重写 `sort_order` |
| `updateRemark(userId, stockCode, remark)` | 更新备注 |
| `refresh(userId)` | 提交异步任务刷新自选股行情 |

`list` 的指标填充复用 `MetricValueService`，与市场列表走同一条链路，避免两套口径。

**跨模块约束**：`watchlist` 模块不得直接调用 `metrics` 模块的 Mapper，只能通过 `MetricValueService` 接口。这一点由代码评审保证（当前实现符合）。

## 三、排序实现

拖动排序由前端计算新顺序，调用 `PUT /watchlist/sort` 提交完整 `stockCodes` 数组。后端在一个事务内：
1. 校验所有代码都属于该用户的自选列表；
2. 按数组下标 `i` 更新 `sort_order = i`。

不使用 `sort_order` 间隔插入法（避免长期使用后值溢出和重排复杂度）。

## 四、异步刷新

`POST /watchlist/refresh` 返回 `taskId`，实际执行由 `@Async` 线程池（`AsyncConfig`）承担：
1. 取该用户全部自选股代码；
2. 按 `QuoteProvider` 批量能力分批（每批 ≤50）请求腾讯；
3. 成功结果写 `stock_quotes`；
4. 写 `data_sync_logs`（`sync_type = WATCHLIST`）。

## 五、前端实现

- 页面：`src/pages/watchlist/index.vue`
- 状态：`src/stores/stock.ts` 中的 watchlist 片段
- 指标列渲染：`StockRow.vue` 接收 `metrics` 动态对象，遍历 `user-config` store 中的 `watchlistMetrics`
- 空态：`LkEmpty.vue`

## 六、已知限制

| 项 | 说明 |
|---|---|
| 无分组 | 第一版单列表 |
| 无上限校验 | 未限制数量 |
| 排序无并发保护 | 单用户场景不涉及，多端同时排序可能互相覆盖 |
