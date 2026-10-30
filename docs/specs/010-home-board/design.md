# 010-首页自选看板与计算引擎 · 技术设计

> 版本：v1.0
> 日期：2026-10-30
> 前置文档：`spec.md`、`docs/database/schema.md`、`docs/specs/004-data-source/source-research.md`

## 一、总体架构

```text
腾讯行情 qt.gtimg.cn ──┐
                       ├─► QuoteSyncScheduler（15s 轮询，仅交易时段）
东方财富 datacenter ────┤        │
  ├─ 分红送配 RPT_SHAREBONUS_DET │   └─► QuoteSnapshotService（Caffeine 缓存 + stock_quotes 落库）
  └─ 财报 RPT_LICO_FN_CPD ────► DividendFinancialSyncService（定时+事件触发）│
                                          │
                              ┌───────────┴────────────┐
                              ▼                        ▼
                        stock_dividends          stock_financials
                        stock_share_events             │
                              │                        │
                              ▼                        ▼
                    calculation 包（计算引擎，BigDecimal）
  DividendYieldCalculator / TargetPriceCalculator / PeTtmCalculator / ShareBasisAdjuster
                              │
                              ▼
              StockIndicatorService ──► GET /api/v1/watchlist/board（HTTP 兜底）
                              │
                              ▼
              QuotePushService ──► WebSocket /ws/stocks/favorites ──► 小程序首页
```

## 二、数据库变更（V5 迁移，只新增不修改历史脚本）

### 2.1 `stock_dividends` 新增字段

```sql
ALTER TABLE stock_dividends ADD COLUMN IF NOT EXISTS dividend_id    VARCHAR(64);   -- 分红方案唯一标识（数据源口径）
ALTER TABLE stock_dividends ADD COLUMN IF NOT EXISTS implementation_date DATE;      -- 实际派息实施日期
CREATE INDEX IF NOT EXISTS idx_dividends_dividend_id ON stock_dividends (dividend_id);
CREATE INDEX IF NOT EXISTS idx_dividends_impl_date ON stock_dividends (stock_id, implementation_date);
```

`dividend_id` 生成规则：`{source_code}:{数据源返回的方案唯一键}`（去重与追溯用）。

### 2.2 新建 `stock_share_events` 股本变动事件表

```sql
CREATE TABLE IF NOT EXISTS stock_share_events (
    id              BIGSERIAL PRIMARY KEY,
    stock_id        BIGINT       NOT NULL,
    event_date      DATE         NOT NULL,               -- 除权除息日（送转生效日）
    event_type      VARCHAR(16)  NOT NULL,               -- BONUS / TRANSFER / SPLIT / REVERSE_SPLIT
    bonus_ratio     NUMERIC(20,6),                       -- 每10股送X股 → X/10
    transfer_ratio  NUMERIC(20,6),                       -- 每10股转增X股 → X/10
    split_ratio     NUMERIC(20,6),                       -- 拆股 1拆N → N；并股 N并1 → 1/N
    source_code     VARCHAR(32)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_share_events UNIQUE (stock_id, event_date, event_type, source_code)
);
CREATE INDEX IF NOT EXISTS idx_share_events_stock_date ON stock_share_events (stock_id, event_date);
```

A 股实际无拆股/并股，但表结构预留；第一版实际数据源只产生 `BONUS`/`TRANSFER`。

### 2.3 指标口径说明修正

V5 内对 `metric_definitions` 中 `dividend_yield_ttm` 的 `pending_questions` 追加「TTM 窗口按 implementation_date 筛选、缺失时 ex_dividend_date 兜底；历史分红按 stock_share_events 换算当前股本口径」。

## 三、计算引擎（`com.lingkan.calculation` 新包）

职责单一、无 Controller 依赖、全部 `BigDecimal`，可独立单元测试。

### 3.1 `ShareBasisAdjuster`（股本换算）

```java
// 输入：分红除息日、每股现金分红、该股票除息日之后（含同日）的股本变动事件
// 输出：换算为当前股本口径的每股现金分红
adjusted = cash × Π(1 / (1 + bonus_ratio + transfer_ratio))   // BONUS/TRANSFER
                 × Π(1 / split_ratio)                          // SPLIT
                 × Π(reverse_ratio)                            // REVERSE_SPLIT
```

- 事件窗口：`event_date >= 分红除息日`（同日送转视为按除息前股本派息，需折减）。
- 无事件时因子为 1，直接返回原值。

### 3.2 `DividendYieldCalculator`

```java
// TTM 每股现金分红：窗口 [T-365天, T]，按 implementation_date 筛选（null 时用 ex_dividend_date）
//   → 过滤 plan_status 含「预案/取消/未实施」的记录
//   → 按 dividend_id 去重
//   → 每笔经 ShareBasisAdjuster 换算后累加
// 现股息率 = TTM每股现金分红 × 100 / 当前股价（HALF_UP 保留 2 位）
// 无有效分红或价格为空/0 → null（对外展示 --）
```

### 3.3 `TargetPriceCalculator`

```java
// 目标股息率配置（application.yml，可扩展）：
// stock.target-yields: 4.0,4.5,5.0,5.5,6.0,6.5,7.0,7.5,8.0
// 目标价格 = TTM每股现金分红 × 100 / 目标股息率（HALF_UP 2 位）
// 输入无效（分红≤0 或 yield≤0）→ null
```

目标价格依赖 TTM 每股分红（非行情），仅分红数据变化时重算；实现上按需惰性计算 + 短缓存。

### 3.4 `PeTtmCalculator`

```java
// TTM归母净利润 = 最近四个连续季度（report_period 倒序取 4，校验期号连续）net_profit_parent 之和
// PE TTM = 总市值 / TTM归母净利润
// TTM>0 → 正常值；=0 或缺失 → null（--）；<0 → LOSS 状态（展示「亏损」）
// 总市值取 QuoteResult.totalMarketCap（行情快照），行情变化即重算
```

### 3.5 `StockIndicatorService`（聚合）

向看板接口输出 `BoardRowDto`：

```java
record BoardRowDto(String stockCode, String stockName, String exchange,
        BigDecimal price, BigDecimal changePercent, BigDecimal changeAmount,
        BigDecimal annualDividendPerShare, BigDecimal dividendYieldTtm,
        BigDecimal peTtm, String peStatus,            // NORMAL / LOSS / N_A
        Map<String, BigDecimal> targetPrices,          // {"4.0":75.00,...}
        LocalDateTime quoteTime, LocalDate dividendDataTime, LocalDate financialDataTime,
        String dataStatus)                             // NORMAL / STALE / SUSPENDED
```

- `MetricValueService` 重构为调用上述计算器（保持现有详情页/市场页行为不变），计算器不反向依赖 Metric 模块。
- 数值缺失一律 `null`（Jackson 非空序列化 → 字段缺省），前端统一渲染 `--`；禁止输出 0 冒充。

## 四、数据源适配器（`datasource.provider.impl`）

### 4.1 `EastMoneyDividendProvider`

- 接口：`https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_SHAREBONUS_DET`
- 返回字段（**实现前真实请求验证，以下为待验证候选**）：`SECURITY_CODE`、`DIVIDENT_START_DATE`（除息日）、`EX_DIVIDEND_DATE`、`PRETAX_BONUS_RMB`（每股派息）、`BONUS_SHARES_RATIO`（送股比例）、`TRANSFER_SHARE_RATIO`（转增比例）、`EQUITY_RECORD_DATE`（登记日）、`PAYMENT_DATE`（派息日）、`PROGRESS`（方案进度）、`REPORT_DATE`（所属年度）。
- 输出：`stock_dividends` 记录 + `stock_share_events` 事件（送/转比例 > 0 时）。
- 限流沿用 500ms/次；连续失败熔断复用 EastMoney 现有熔断经验。

### 4.2 `EastMoneyFinancialProvider`

- 接口：`https://datacenter-web.eastmoney.com/api/data/v1/get?reportName=RPT_LICO_FN_CPD`
- 关键字段（待实测验证）：`SECURITY_CODE`、`REPORT_DATE`（报告期）、`TOTAL_OPERATE_INCOME`、`PARENT_NETPROFIT`、`EPSJB`（基本每股收益）、`PUBLISH_DATE`（披露日期）。
- 输出：`stock_financials`（`report_period` = 报告期季度格式 `YYYYQn`，`report_type`、`publish_date` 保留）。

### 4.3 同步服务 `DividendFinancialSyncService`

- 范围（Q10-1 建议）：自选股 + 详情页访问过的股票（`stock_dividends`/`stock_financials` 为空时惰性补同步）。
- 触发：每日 05:30 定时（自选股增量）；添加自选成功后异步即时同步；手动接口 `/api/v1/sync/dividend-financial`。
- 去重：分红按 `dividend_id` 三级匹配——① `dividend_id`；②「归属年度 + 除息日」；③「归属年度 + 公告日」兜底（预案→实施演进时除息日从无到有、`dividend_id` 锚点变化，仍匹配原记录原地更新）；财务按 `(stock_id, report_period, source_code)`。
- 股本事件：仅对「已实施（`DividendStatusRules`）+ 除息日存在 + 比例 > 0」的方案生成 BONUS/TRANSFER 事件。
- 数据源失败语义：Provider 失败返回 null（MISSING），同步服务据此计失败并保留旧数据；成功无记录返回空列表。
- 写 `data_sync_logs`（`sync_type = DIVIDEND / FINANCIAL`）。

## 五、行情轮询与 WebSocket

### 5.1 轮询（改造 `QuoteSyncScheduler`）

```yaml
stock:
  quote:
    enabled: true            # 复用 lingkan.sync.enabled 语义
    interval-seconds: 15
    market-hours-only: true  # 09:30-11:30 / 13:00-15:00
    batch-size: 100
    cache-ttl-seconds: 60
    websocket-enabled: true
```

- 轮询范围：全量自选股（当前单用户，未来多用户取并集）。
- 结果进 Caffeine 缓存（`QuoteSnapshotService` 现有 20s 缓存调整为共享语义）→ 仅价格变化时落库 `stock_quotes`（避免 15s 高频写库，沿用现有唯一约束更新行）→ 触发 WebSocket 推送。
- 非交易时段：停止高频轮询，保留最后有效行情，首页标注行情时间。
- 行情接口失败：保留最近有效行情，`dataStatus = STALE`。

### 5.2 WebSocket（新增依赖 `spring-boot-starter-websocket`）

- 端点 `/ws/stocks/favorites`（单用户 `local-user`，无鉴权复杂体系）。
- 消息：`{"type":"STOCK_QUOTE_UPDATE","data":[{stockCode,price,changePercent,dividendYieldTtm,peTtm,quoteTime}]}`。
- 推送前经计算引擎重算股息率与 PE（目标价格不随行情重算）。
- 连接断开不阻塞 HTTP 兜底；小程序重连后调 board 接口取全量快照。

## 六、后端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/watchlist/board` | **新增**：看板聚合（BoardRowDto 列表 + 行情更新时间） |
| GET/POST/DELETE/PUT | `/api/v1/watchlist[/...]` | 沿用现有（添加自选时触发分红/财务即时同步） |
| GET | `/api/v1/stocks/search` | 沿用现有 |
| GET | `/api/v1/stocks/{code}/dividends` | 沿用现有（返回补充 dividend_id/implementation_date） |
| POST | `/api/v1/sync/dividend-financial` | **新增**：手动触发分红/财务同步（异步返回 taskId） |
| WS | `/ws/stocks/favorites` | **新增**：行情推送 |

响应沿用 `ApiResponse{code,message,data}` 规范。`targetPrices` 为字符串键 map：`{"4.0":75.00,...,"8.0":37.50}`。

## 七、前端首页改造（`pages/home/index`）

### 7.1 结构

```text
pages/home/index.wxml
├─ 顶栏：品牌「玲看股息」+ 搜索按钮 + 添加自选 + 刷新状态（行情时间/交易时段）
├─ 表头行（固定，不随纵向滚动）
├─ 首列固定区：股票名称 + 代码（两行）
└─ 横向滚动区（scroll-view scroll-x）：
    现价 | 涨跌幅 | 现股息率 | PE TTM | 4% | 4.5% | ... | 8%（9 档目标价）
```

- 纵向滚动：外层 scroll-view（首列与右侧同步 scroll-top），表头 fixed 在滚动区外。
- 横向滚动冲突：优先「首列固定 + 右侧 scroll-x」单层方案；若原生组件兼容性出问题，按方案 2.3 退化为双层同步滚动。
- 颜色：涨红跌绿（沿用规范）；股息率统一强调色；目标价格达标（现价 ≤ 目标价）高亮该格；PE 亏损显示「亏损」、缺失显示 `--`。
- 每行独立 `setData`（`stock[{{index}}].price` 路径），WS 推送只更新变化行，不整页重渲染。

### 7.2 数据流

- `onLoad` → board 接口全量快照 → 渲染；`onShow`（后台回前台）→ 重取快照。
- WebSocket：`wx.connectSocket` → 收到 `STOCK_QUOTE_UPDATE` 按 stockCode 更新行；`onClose/onError` 指数退避重连，重连成功后重新拉 board 快照；`onHide` 关闭连接。
- 下拉刷新（`enablePullDownRefresh: true`）→ 重拉 board 快照 + 触发后端自选行情刷新。
- 移除现有市场概览 hero 与「全市场筛选」入口（TabBar 不变）。
- 空状态：文案按方案 7.4，提供「添加股票」按钮。
- 新增 `services/board.js`（board 接口 + WS 地址拼接）；WS 地址从 `config/index.js` 的 baseUrl 派生。

### 7.3 性能

- 50 行 × 15 列以原生 view 渲染；WS 高频更新仅命中变化行路径。
- 目标价格仅在 board 快照中计算（分红变化才变），WS 消息不携带目标价格。

## 八、测试设计（对应方案第十一节）

| 类 | 用例 |
|---|---|
| DividendYieldCalculatorTest | 3 元/50 元=6%；48 元=6.25%；55 元≈5.45%；价格 0/null；12 个月窗口内外；未实施/预案剔除；同年度多次；跨年度；重复公告去重；股本送转后换算（10转10 后 3 元 → 1.5 元口径） |
| TargetPriceCalculatorTest | 3 元 → 4%:75.00 / 5%:60.00 / 6%:50.00 / 7%:42.86 / 8%:37.50；分红为 0/null；目标档位为空 |
| PeTtmCalculatorTest | 500 亿/50 亿=10；600 亿→12；400 亿→8；利润 0→null；利润负→LOSS；不足 4 季度→null；期号不连续→null |
| ShareBasisAdjusterTest | 送股 10送10、转增 10转5、送+转叠加、拆股 1拆2、并股 2并1、同日事件、无事件 |
| ProviderTest | 适配器字段解析（真实报文样本固定化） |
| 集成（手动） | 行情轮询、WS 推送/断线重连、多用户共享缓存、停牌/非交易时段 |

宪法铁律 11 要求的异常分支全部覆盖。

## 九、实施顺序（对应方案第十二节六阶段）

1. 现状分析（已完成，见 spec.md 差异表）
2. V5 迁移 + 实体
3. 分红/财报适配器（真实请求验证字段）→ 同步服务
4. 计算引擎四件套 + 单元测试
5. 看板聚合接口
6. 行情轮询 + WebSocket
7. 小程序首页表格
8. 联调验收 + 文档同步（010/005/008 + changelog）+ commit/push

每完成一个功能：运行测试 → 更新文档 → 立即 commit 并 push origin/main（宪法铁律 13）。

## 十、风险与限制

| 项 | 说明 |
|---|---|
| 东财接口非公开文档 | 字段以真实请求为准；限流 500ms/次 + 熔断；失败保留旧数据 |
| 股本换算口径 | 送转事件同日归属、拆并股预留字段未经 A 股真实数据验证，以单元测试 + 真实样本核对 |
| WebSocket 单实例 | 单实例部署下会话在本进程；多实例需引入 Redis pub/sub（不在本版范围） |
| 15 秒非数据源保证 | 轮询间隔为发起间隔，实际时效以接口返回 `data_time` 为准并在首页标注 |
