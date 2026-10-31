# 010-首页自选看板与计算引擎 · 技术设计

> 版本：v1.2
> 日期：2026-10-31
> 前置文档：`spec.md`、`docs/database/schema.md`、`docs/specs/004-data-source/source-research.md`、`docs/specs/007-history/spec.md`

## 一、总体架构

```text
腾讯行情 qt.gtimg.cn ──┐
                       ├─► QuoteSyncScheduler（15s 轮询，仅交易时段）
东方财富 datacenter ────┤        │
  ├─ 分红送配 RPT_SHAREBONUS_DET │   └─► QuoteSnapshotService（Caffeine 缓存 + stock_quotes 落库）
  ├─ 财报 RPT_LICO_FN_CPD ────► DividendFinancialSyncService（定时+事件触发）│
  └─ 周K kline klt=102 fqt=1 ─► WeeklyKlineSyncService（交易日 60min + 添加自选 + 手动）
                                          │
                              ┌───────────┴────────────┬──────────────────┐
                              ▼                        ▼                  ▼
                        stock_dividends          stock_financials   stock_weekly_quotes
                        stock_share_events             │                  │
                              │                        │                  │
                              ▼                        ▼                  ▼
                    calculation 包（计算引擎，BigDecimal）
  DividendYieldCalculator / TargetPriceCalculator / PeTtmCalculator / ShareBasisAdjuster
                              / WeeklyIndicatorCalculator（v1.2 新增）
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

### 2.4 V6 迁移（v1.2 新增：周线表 + 周线指标登记）

```sql
CREATE TABLE IF NOT EXISTS stock_weekly_quotes (
    id              BIGSERIAL PRIMARY KEY,
    stock_id        BIGINT        NOT NULL,
    trade_date      DATE          NOT NULL,   -- 周线所属周末交易日（东财返回口径）
    open_price      NUMERIC(20,4),
    close_price     NUMERIC(20,4),
    high_price      NUMERIC(20,4),
    low_price       NUMERIC(20,4),
    volume          NUMERIC(24,4),            -- 手
    amount          NUMERIC(24,4),            -- 元
    adjustment_type VARCHAR(16)   NOT NULL,   -- QFQ（前复权）
    source_code     VARCHAR(32)   NOT NULL,
    fetch_time      TIMESTAMP     NOT NULL,
    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_weekly_quotes UNIQUE (stock_id, trade_date, adjustment_type, source_code)
);
CREATE INDEX IF NOT EXISTS idx_weekly_quotes_stock_date ON stock_weekly_quotes (stock_id, trade_date);
```

指标登记（只新增/更新登记项，不删除历史行，铁律 5）：

| metric_code | 处理 | calculation_status | calculation_expression |
|---|---|---|---|
| `week_120` | **新增** | `READY` | `MA(周线收盘价, 120)` 前复权周线 |
| `week_60` | 更新（原 PENDING） | `READY` | `MA(周线收盘价, 60)`，与 `ma_60w` 同口径 |
| `week_middle` | 更新（原 PENDING） | `READY` | `MA(周线收盘价, 20)`（周线布林中轨，N=20） |
| `week_upper` | 更新（原 PENDING） | `READY` | `周中 + 2 × STD(周线收盘价, 20)`（样本标准差） |
| `week_lower` | 更新（原 PENDING） | `READY` | `周中 − 2 × STD(周线收盘价, 20)`（样本标准差） |
| `week_30` | **不动** | `PENDING` | Q7-1 未确认，本轮不实现 |
| `band_position` | **不动** | `PENDING` | Q7-5 未确认，本轮不实现 |
| `industry` | 已存在 | `READY` | 直接取 `stocks.industry`，看板列复用，不新建指标 |

`week_60` 与既有 `ma_60w` 是同一口径的两个登记项：实现只有一份（`WeeklyIndicatorCalculator`），看板取 `week_60`/`week_120` 命名以对齐用户口语「60周/120周」；不删除 `ma_30w`/`ma_60w`（历史登记保留）。

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
// lingkan.board.target-yields: 4.0,4.5,5.0,5.5,6.0,6.5      // v1.2 起六档（移除 7.0/7.5/8.0）
// 目标价格 = TTM每股现金分红 × 100 / 目标股息率（HALF_UP 2 位）
// 输入无效（分红≤0 或 yield≤0）→ 该档 null（对外展示 --，禁止 0 冒充）
```

目标价格依赖 TTM 每股分红（非行情），仅分红数据变化时重算；实现上按需惰性计算 + 短缓存。

> 说明：配置键落地为 `lingkan.board.target-yields`（跟随项目 `lingkan.*` 配置惯例，
> 经 `BoardProperties` 绑定）；未配置时回退默认档位。**v1.2：默认档位与配置值同步收窄为
> 4.0%~6.5% 六档**（用户 2026-10-31 裁决 ⑦），`TargetPriceCalculator.DEFAULT_TARGET_YIELDS`
> 与 `application.yml` 必须保持一致，前端展开区按后端返回的键渲染，不写死档位数量。

### 3.4 `PeTtmCalculator`

```java
// 数据源 PARENT_NETPROFIT 为报告期累计值，须先换算单季（禁止直接累加累计值）：
//   Q1 → 本期值；H1 → 本期 − 同年Q1；Q3 → 本期 − 同年H1；年报 → 本期 − 同年Q3
// TTM归母净利润 = 最近四个连续季度（report_period 倒序取 4，校验期号连续）单季值之和
// PE TTM = 总市值 / TTM归母净利润
// TTM>0 → 正常值；=0 或缺失 → null（--）；<0 → LOSS 状态（展示「亏损」）
// 总市值取 QuoteResult.totalMarketCap（行情快照），行情变化即重算
```

### 3.5 `StockIndicatorService`（聚合）

向看板接口输出 `BoardRowDto`（v1.2 字段）：

```java
record BoardRowDto(String stockCode, String stockName, String exchange,
        String industry,                              // v1.2 新增：stocks.industry，缺失 null
        BigDecimal price, BigDecimal changePercent, BigDecimal changeAmount,
        BigDecimal annualDividendPerShare, BigDecimal dividendYieldTtm,
        BigDecimal peTtm, String peStatus,            // NORMAL / LOSS / N_A
        BigDecimal ma60w, BigDecimal ma120w,          // v1.2 新增：60周/120周均线
        BigDecimal weekUpper, BigDecimal weekMiddle, BigDecimal weekLower,   // v1.2 新增：周线布林轨
        String weeklyStatus,                          // NORMAL / INSUFFICIENT / MISSING
        LocalDate weeklyDataTime,                     // 最新周线所属周末交易日
        Map<String, BigDecimal> targetPrices,          // v1.2 起六档 {"4.0":75.00,...,"6.5":...}
        LocalDateTime quoteTime, LocalDate dividendDataTime, LocalDate financialDataTime,
        String dataStatus)                             // NORMAL / STALE / SUSPENDED
```

- `MetricValueService` 重构为调用上述计算器（保持现有详情页/市场页行为不变），计算器不反向依赖 Metric 模块。
- 数值缺失一律 `null`（Jackson 非空序列化 → 字段缺省），前端统一渲染 `--`；禁止输出 0 冒充。
- 周线取数：`resolveAll` 用**一次** `in (stockIds)` 批量查询 `stock_weekly_quotes` 后在内存分组，避免 N+1；单只路径（`resolve` / `resolveQuoteUpdate`）按 `stock_id` 单查。看板路径**只读本地表**，不触发上游请求（FR-10.14）。
- 本周未收盘覆盖：若最新周线日期与行情 `data_time` 同属一个 ISO 周，则用行情最新价替换该根周线收盘价后再算（MA 与 BOLL 均只用收盘价），使周线五指标随行情变化；不同 ISO 周则不覆盖（避免用今天的价覆盖上周的线）。

### 3.6 `WeeklyIndicatorCalculator`（v1.2 新增）

```java
// 输入：按日期升序的前复权周线收盘价序列（本周已按 §3.5 规则覆盖为实时价）+ 参数（maShort=60, maLong=120, bollN=20, bollK=2）
// 输出：record WeeklyIndicators(BigDecimal ma60w, ma120w, bollUpper, bollMiddle, bollLower,
//                               String status, String reason, int barCount, LocalDate lastBarDate)
// MA(N)  = 最近 N 根周收盘价算术平均（HALF_UP 2 位）；可用根数 < N → 该值 null
// BOLL   = 中轨 MA(bollN)；σ = 样本标准差（除以 N−1）；上轨 = 中轨 + K×σ；下轨 = 中轨 − K×σ
// 状态   = NORMAL（至少一个指标算出）/ INSUFFICIENT（根数不足）/ MISSING（无周线数据）
// 异常防护：窗口内任一收盘价 ≤ 0（东财前复权对高分红老股会产生负价）→ 该窗口指标全部 null，
//           status = INSUFFICIENT 并在 reason 说明「周线数据异常（前复权收盘价非正）」
// 纯计算、无 Spring 依赖、可独立单元测试（铁律 11）
```

参数经 `BoardProperties.weekly`（`lingkan.board.weekly.*`）绑定：`ma-short`、`ma-long`、`boll-n`、`boll-k`、`bars-limit`（默认 130，≥ ma-long + 余量）、`refresh-interval-ms`（默认 3600000）、`enabled`。改数值不改代码（铁律 2）。

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

### 4.4 `EastMoneyWeeklyKlineProvider` + `WeeklyKlineSyncService`（v1.2 新增）

**接口（2026-10-31 真实请求验证，非推测）**：

```text
https://push2his.eastmoney.com/api/qt/stock/kline/get
  ?secid={1|0}.{code}          # 沪 1. / 深与北 0.，复用 EastMoneyProvider.secid()
  &fields1=f1,f2,f3,f4,f5,f6
  &fields2=f51,f52,f53,f54,f55,f56,f57,f58
  &klt=102                      # 102 = 周线
  &fqt=1                        # 1 = 前复权
  &end=20500101&lmt={bars-limit}
```

实测样本：平安银行 `secid=0.000001` 返回 `dktotal=8507`，`data.klines` 为字符串数组，
逗号分隔字段序 `日期,开盘,收盘,最高,最低,成交量(手),成交额(元),振幅(%)`，例如
`"2026-10-30,11.15,11.64,11.67,11.13,8397163,9584245034.51,4.82"`；日期为该周最后一个交易日。
贵州茅台 `secid=1.600519` 2001 年周线在 `fqt=0` 为 34.51 等正值、`fqt=1` 为 **−312.68 等负值**，
证实前复权会对累计分红极大的老股产生负价（计算器必须防护，见 §3.6）。

**Provider 契约**：与 `DividendProvider` 一致——失败（非 200 / 网络 / 解析异常）返回 `null`（MISSING），
成功但无 K 线返回空列表；输出 `WeeklyBar(tradeDate, open, close, high, low, volume, amount)`。
超时 10s，UA 与其他东财适配器一致，限流沿用 500ms/次。

**同步服务**（结构对齐 `DividendFinancialSyncService`）：

- 范围：全部用户自选股并集（`WatchlistService.listAllFavoriteStocks()`）。
- 触发：① 交易日按 `refresh-interval-ms`（默认 60 分钟）定时；② 添加自选成功后异步即时同步；③ 手动 `POST /api/v1/sync/weekly-kline`。
- 写入：整窗口 upsert（按 `(stock_id, trade_date, adjustment_type, source_code)` 先查后插/更新，openGauss 不用 `ON CONFLICT`），每次覆盖最近 `bars-limit` 根——前复权基准随最新除权事件漂移，禁止只增量追加。
- 留痕：`data_sync_logs`，`sync_type = WEEKLY_KLINE`；批次互斥沿用 `AtomicBoolean`，被拒绝的提交返回 `accepted=false`。
- 失败降级：单只失败只记日志并保留库内旧数据，周线列显示 `--`，不影响其余列与整页。

## 五、行情轮询与 WebSocket

### 5.1 轮询（改造 `QuoteSyncScheduler`）

```yaml
lingkan:
  sync:
    enabled: false                # 部署确认后显式开启
    quote-interval-ms: 15000      # fixedDelay，上次结束后 15 秒再执行
    initial-delay-ms: 30000
    market-hours-only: true       # 工作日 09:30-11:30 / 13:00-15:00
    quote-batch-size: 100
    quote-cache-ttl-seconds: 60
    quote-stale-after-seconds: 90
    websocket-enabled: true
```

- 轮询范围：所有用户自选股并集，按代码排序后分批；空自选不请求上游。
- `QuoteSnapshotService` 缓存改为按股票代码共享：调度 `refreshQuotes` 绕过新鲜缓存并写入，HTTP `getQuotes` 优先复用；仅价格变化时落库 `stock_quotes`，只推送价格变化行。
- 非交易时段：调度短路，不请求上游，保留最后有效行情，首页标注行情时间。
- 行情接口失败：逐源异常隔离并回退；仍无结果时读取 `stock_quotes` 最近快照，不写入新鲜缓存，`fetchTime` 超过阈值后 `dataStatus = STALE`。
- 法定节假日暂未单独维护；当前交易时段判断覆盖周末与每日开闭市边界。

### 5.2 WebSocket（新增依赖 `spring-boot-starter-websocket`）

- 端点 `/ws/stocks/favorites`（单用户 `local-user`，无鉴权复杂体系）。
- 消息：`{"type":"STOCK_QUOTE_UPDATE","data":[{stockCode,price,changePercent,dividendYieldTtm,peTtm,ma60w,ma120w,weekUpper,weekMiddle,weekLower,weeklyStatus,weeklyDataTime,quoteTime,dataStatus}]}`。
- 推送前经计算引擎重算股息率、PE 与**周线五指标**（v1.2：本周未收盘周线收盘价用实时价覆盖，故周线值随行情变化，必须随推送下发；目标价格不随行情重算，仍不在推送负载内）。
- 连接断开不阻塞 HTTP 兜底；小程序重连后调 board 接口取全量快照。

## 六、后端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/watchlist/board` | **新增**：看板聚合（BoardRowDto 列表 + 行情更新时间） |
| GET/POST/DELETE/PUT | `/api/v1/watchlist[/...]` | 沿用现有（添加自选时触发分红/财务即时同步） |
| GET | `/api/v1/stocks/search` | 沿用现有 |
| GET | `/api/v1/stocks/{code}/dividends` | 沿用现有（返回补充 dividend_id/implementation_date） |
| POST | `/api/v1/sync/dividend-financial` | **新增**：手动触发分红/财务同步（异步返回 taskId） |
| POST | `/api/v1/sync/weekly-kline` | **新增（v1.2）**：手动触发自选股周线同步（异步返回 jobId，可传 stockCodes 限定范围） |
| WS | `/ws/stocks/favorites` | **新增**：行情推送 |

响应沿用 `ApiResponse{code,message,data}` 规范。`targetPrices` 为字符串键 map：v1.2 起六档 `{"4.0":..,"4.5":..,"5.0":..,"5.5":..,"6.0":..,"6.5":..}`。

## 七、前端首页改造（`pages/home/index`）

### 7.1 结构

```text
pages/home/index.wxml
├─ 顶栏：我的自选 + 添加股票 + 刷新状态（行情时间）
├─ 表格（v1.2：横向滚动 + 首列固定）
│   ├─ 首列（sticky left）：股票名称 / 代码 · 交易所 / 行业小字
│   └─ 右侧 9 列（scroll-x）：现价 | 涨跌幅 | 股息率 TTM | PE TTM | 60周 | 120周 | 周上 | 周中 | 周下
└─ 纵向：页面级滚动（不再用固定高度内层 scroll-y），表头 sticky top
    └─ 行内展开区：4% | 4.5% | 5% | 5.5% | 6% | 6.5% 六档目标价（3×2 网格）
```

- 横向滚动：整表放在一个 `scroll-view scroll-x` 内，表头与表体同处该滚动容器，列宽用同一份 WXSS 变量保证对齐；首列与表头用 `position: sticky`（首列 `left: 0`、表头 `top: 0`，交叉单元格两者都加）固定。
- 纵向滚动交给页面（`enablePullDownRefresh` 仍可用），避免嵌套双向 scroll-view 的手势冲突；`.board-body` 不再写死 `calc(100vh - ...)` 高度。
- 展开状态：使用独立 `expandedMap[stockCode]`，切换只更新该键，不重排或重取 rows；展开区完整展示六档目标价，名称/代码仍可单独进入详情。
- 颜色：涨红跌绿（沿用规范）；股息率统一强调色；目标价格达标（现价 ≤ 目标价）高亮该格；PE 亏损显示「亏损」、缺失显示 `--`；周线五列纯数值右对齐，缺失 `--`，不做涨跌着色（它们是价位参考线，不是涨跌）。
- 每行独立 `setData`（`rows[{{index}}].price` 路径），WS 推送只更新变化行（含周线五列），不整页重渲染。

### 7.2 数据流

- `onLoad` → board 接口全量快照 → 渲染；`onShow`（后台回前台）→ 重取快照。
- WebSocket：`wx.connectSocket` → 收到 `STOCK_QUOTE_UPDATE` 按 stockCode 更新行；`onClose/onError` 指数退避重连，重连成功后重新拉 board 快照；`onHide` 关闭连接。
- 下拉刷新（`enablePullDownRefresh: true`）→ 重拉 board 快照 + 触发后端自选行情刷新。
- 移除现有市场概览 hero 与「全市场筛选」入口（TabBar 不变）。
- 空状态：文案按方案 7.4，提供「添加股票」按钮。
- 新增 `services/board.js`（board 接口 + WS 地址拼接）；WS 地址从 `config/index.js` 的 baseUrl 派生。

### 7.3 性能

- 默认 50 行 × 10 列以原生 view 渲染；六档目标价只在对应行展开时渲染，WS 高频更新仅命中变化行路径。
- 目标价格仅在 board 快照中计算（分红变化才变），WS 消息不携带目标价格；周线五列随 WS 下发（本周口径下它们随实时价变化）。
- 展开状态与行情数据分离，推送不折叠已展开行。
- 横向滚动容器内元素数量 = 行数 × 11 个单元格；50 行约 550 个 view，仍在小程序可接受范围；若后续自选超过 100 只，需引入虚拟列表（本版不做）。

## 八、测试设计（对应方案第十一节）

| 类 | 用例 |
|---|---|
| DividendYieldCalculatorTest | 3 元/50 元=6%；48 元=6.25%；55 元≈5.45%；价格 0/null；12 个月窗口内外；未实施/预案剔除；同年度多次；跨年度；重复公告去重；股本送转后换算（10转10 后 3 元 → 1.5 元口径） |
| TargetPriceCalculatorTest | 3 元 → 4%:75.00 / 5%:60.00 / 6%:50.00 / 6.5%:46.15；**默认档位为六档 4.0~6.5（v1.2）**；分红为 0/null；目标档位为空 |
| PeTtmCalculatorTest | 500 亿/50 亿=10；600 亿→12；400 亿→8；利润 0→null；利润负→LOSS；不足 4 季度→null；期号不连续→null |
| ShareBasisAdjusterTest | 送股 10送10、转增 10转5、送+转叠加、拆股 1拆2、并股 2并1、同日事件、无事件 |
| **WeeklyIndicatorCalculatorTest** | MA60/MA120 精确值（构造等差序列手算比对）；根数 59/119 → null 且 status=INSUFFICIENT；BOLL20 中轨=MA20、上下轨=中轨±2σ（手算样本）；σ 为样本标准差（n−1）验证；收盘价含 0/负值 → 全部 null；空列表/null → MISSING；乱序输入内部排序；本周覆盖生效（同 ISO 周）与不生效（跨 ISO 周）两分支 |
| **EastMoneyWeeklyKlineProviderTest** | 固定真实报文样本解析（8 根周线，字段序 日期/开/收/高/低/量/额/振幅）；`data` 为 null → 空列表；非法 JSON → null；klines 缺字段/日期非法 → 跳过该行 |
| ProviderTest | 适配器字段解析（真实报文样本固定化） |
| 集成（手动） | 行情轮询、WS 推送/断线重连、多用户共享缓存、停牌/非交易时段、周线同步与看板列展示 |

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

**v1.2 增量（2026-10-31）**：

9. 行业列 + 目标价档位收窄六档（后端 DTO/配置/默认值 + 前端）→ 测试 → 文档 → commit/push
10. V6 迁移（周线表 + 指标登记）→ 东财周 K 适配器（真实报文单测）→ 周线同步服务/调度/手动接口
11. `WeeklyIndicatorCalculator` + 单测 → 看板聚合与 WS 负载接入
12. 首页横向滚动表格 + 首列固定 + 五个周线列 → 开发者工具运行时验证 → 文档同步 → commit/push

每完成一个功能：运行测试 → 更新文档 → 立即 commit 并 push origin/main（宪法铁律 13）。

## 十、风险与限制

| 项 | 说明 |
|---|---|
| 东财接口非公开文档 | 字段以真实请求为准；限流 500ms/次 + 熔断；失败保留旧数据 |
| 股本换算口径 | 送转事件同日归属、拆并股预留字段未经 A 股真实数据验证，以单元测试 + 真实样本核对 |
| WebSocket 单实例 | 单实例部署下会话在本进程；多实例需引入 Redis pub/sub（不在本版范围） |
| 15 秒非数据源保证 | 轮询间隔为发起间隔，实际时效以接口返回 `data_time` 为准并在首页标注 |
| **前复权负价（v1.2）** | 东财 `fqt=1` 对累计分红极大的老股返回负价格（实测茅台 2001 周线 −312.68）。计算窗口只取最近 130 根，通常不触及；仍强制「窗口内任一收盘价 ≤ 0 → 指标 `--`」防护，避免输出无意义数值 |
| **前复权基准漂移（v1.2）** | 每次除权除息后全部历史前复权值整体重算，因此每次同步整窗口覆盖 upsert；库内早于窗口的旧行基准过期，计算只读最近 `bars-limit` 根，不跨基准混用 |
| **周线时效（v1.2）** | 已收盘周线每日至多变化一次；本周未收盘部分由实时价覆盖，故盘中数值随行情变化，但本周的最高/最低/成交量按最近一次同步（默认 60 分钟）为准——布林与均线只用收盘价，不受影响 |
| **十列可读性（v1.2）** | 横向滚动 + 首列固定后一屏仍只能看到约 5~6 列，需滑动查看周线列；这是用户在「横滚 / 两行指标 / 放展开区」三选项中明确选择的结果 |
