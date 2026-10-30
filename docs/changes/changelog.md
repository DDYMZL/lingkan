# 变更日志

> 遵循「变更必须同步」原则：任何代码、口径、数据结构变更均需在此登记。

---

## [0.9.0] - 2026-10-31

### 新增：小程序首页自选看板基线（spec 010 阶段六）

- 新增 `services/board.js`：封装 `GET /watchlist/board`、从环境 `baseUrl` 派生 WS/WSS 地址，以及指数退避重连、消息解析和页面退出释放连接。
- 首页改为 15 列固定顺序看板：股票名称/代码、现价、涨跌幅、股息率 TTM、PE TTM、4%~8% 九档目标价；表头与表体横向滚动同步，首列 sticky 固定，表体独立纵向滚动。
- 行情涨红跌绿，股息率使用主题强调色；现价达到目标价时高亮目标格，PE 亏损、行情过期与停牌有明确展示，缺失值统一 `--`。
- WebSocket 按股票代码写 `rows[index].*` 路径，只更新变化行；目标价不随推送重算，但其达标状态随最新现价更新。断线重连成功、返回前台均重新拉取完整 HTTP 快照。
- 下拉刷新与手动刷新同时提交后端行情刷新并读取看板；搜索页添加自选后返回首页自动刷新。首屏失败展示独立重试态，避免把请求失败误显示为空自选。
- 移除首页市场概览、动态指标偏好列和「全市场筛选」入口；TabBar 全市场入口保留。

### 验证

- 前端全部 JavaScript 通过 `node --check`，全部 JSON 可解析，WXML 标签配对检查通过。
- Node 运行时桩验证通过：WS/WSS 地址派生、首次连接与断线重连识别、非法消息隔离、行级价格/股息率/PE/目标价达标状态与行情时间更新。
- 微信开发者工具、真机 WebSocket 与真实行情联调尚未执行。

### 文档同步

- `docs/specs/010-home-board/{design,tasks}.md`：阶段六任务完成，记录 sticky 首列与表头滚动同步实现。
- `.code-docs/`：新增 board service 文档，更新首页及受影响的配置/自选服务文档（不入库，见其 CHANGELOG）。

---

## [0.8.0] - 2026-10-31

### 新增：自选行情 15 秒轮询与 WebSocket 推送（spec 010 阶段五）

- `QuoteSyncScheduler` 从日志骨架落地为真实调度：默认 15 秒 fixedDelay、30 秒首次延迟、所有用户自选并集、按代码排序和 100 只分批；总开关默认仍为 false，仅工作日交易时段请求上游。
- `QuoteSnapshotService` 改为按股票代码共享 Caffeine 缓存（TTL 60 秒）：调度 `refreshQuotes` 强制刷新，HTTP `getQuotes` 优先复用；新增 `persistChanged`，BigDecimal 按数值判等，相同现价不写库。
- 上游逐源异常隔离；全部无结果时读取 `stock_quotes` 最近快照，不用空值覆盖旧行情。兜底不写新鲜缓存，采集时间超过 90 秒后由看板标记 `STALE`。
- 新增 `spring-boot-starter-websocket`、`WebSocketConfig` 与 `QuotePushService`，端点 `/ws/stocks/favorites`；单实例会话管理，发送失败清理，不阻塞 HTTP 看板。
- 仅价格变化行触发 `resolveQuoteUpdate` 重算股息率 TTM 与 PE TTM；`STOCK_QUOTE_UPDATE` 负载不含九档目标价，目标价仍由 board 快照提供。
- 新增 `QuoteSyncProperties`、`TradingSessionChecker`；法定节假日暂未单独维护。

### 测试

- 新增 9 个测试：共享缓存/强制刷新、持久化兜底/STALE、相同现价跳过写库、交易时段边界、非交易短路、分批与仅变化推送、WebSocket 消息不含目标价。
- 后端全量 83 测试通过。

### 文档同步

- `docs/specs/010-home-board/{design,tasks}.md`：阶段五完成，配置键与降级语义按实现更新，状态「阶段五完成，阶段六开工」。
- `.code-docs/`：新增 9 篇，更新 QuoteSnapshotService、QuoteSyncScheduler、StockIndicatorService、WatchlistService、application、pom 6 篇（不入库，见其 CHANGELOG）。

---

## [0.7.0] - 2026-10-31

### 新增：首页自选看板聚合接口（spec 010 阶段四）

- 新增 `GET /api/v1/watchlist/board`，返回 `BoardResponse{rows,quoteTime,count}`；看板不分页，行顺序与用户自选 `sortOrder` 一致。
- `WatchlistService` 抽取 `listStocks` 作为列表与看板的共同查询入口，跳过已删除股票；空自选直接返回空看板，不触发行情与指标计算。
- 看板行复用 `StockIndicatorService` 与 `BoardRowDto`，保留行情、股息率 TTM、PE TTM、九档目标价及各数据时间；顶层 `quoteTime` 取所有行最新非空行情时间。
- 分红接口核对完成：`StockController.dividends` 直接返回 `StockDividend`，现有实体已含 `dividendId`/`implementationDate`，无需新增 DTO 或重复映射。

### 测试

- 新增 `WatchlistServiceTest` 2 用例：空看板短路、按自选顺序聚合并取最新行情时间。
- 后端全量 74 测试通过。

### 文档同步

- `docs/specs/010-home-board/tasks.md`：T4-01、T4-04 标记完成，状态「阶段四完成，阶段五开工」。
- `.code-docs/`：新增 BoardResponse、WatchlistServiceTest 2 篇，更新 WatchlistService、WatchlistController 2 篇（不入库，见其 CHANGELOG）。

---

## [0.6.0] - 2026-10-30

### 新增：计算引擎四件套（spec 010 阶段三）

**计算器（`com.lingkan.calculation` 新包，纯静态、无 Spring 依赖）**
- `ShareBasisAdjuster`：股本换算，`adjusted = cash × Π(1/(1+bonus+transfer)) × Π(1/split_ratio)`；事件窗口 `event_date >= 除息日`（含同日）；除息日缺失保守不折减
- `DividendYieldCalculator`：股息率 TTM——窗口 [T-365,T] 按实施日期筛选（缺失时除息日兜底）、`DividendStatusRules` 过滤未实施、dividend_id 去重（优先保留有实施日期者）、逐笔股本换算后累加；`yieldPercent` = 每股分红×100/股价（HALF_UP 2 位）；无有效数据返回 null（禁止 0 冒充）
- `TargetPriceCalculator`：目标价 = TTM 每股分红×100/目标收益率（HALF_UP 2 位）；档位可配置（`lingkan.board.target-yields`，经 `BoardProperties` 绑定，默认 4.0~8.0 九档）；无效输入该档为 null（键齐全）
- `PeTtmCalculator`：累计值→单季值换算（Q1 直取 / H1−Q1 / Q3−H1 / 年报−Q3）+ 最近四期连续性校验；TTM>0 → NORMAL、<0 → LOSS（pe 为负值）、=0/缺失/不连续 → N_A（pe=null + reason）

**聚合**
- `StockIndicatorService` + `BoardRowDto`：看板行 = 行情快照 + 股息率 TTM + PE TTM + 9 档目标价（字段契约见 spec 010 §3.5；dataStatus NORMAL/STALE/SUSPENDED；LOSS 时 peTtm=null、peStatus=LOSS）

**口径变更（重要：calculation_version 已提升）**
- `pe_ttm` V1→V2：旧口径把最近 4 条累计值直接求和（TTM 严重高估且无连续性校验），新口径单季换算 + 连续性校验，`MetricValueService` 改用 `CalcVersion.PE_TTM_V2`
- `dividend_yield_ttm` V1→V2：旧口径按除息日过滤、关键词手工匹配状态、无去重、无股本换算；新口径实施日期优先 + 状态规则类 + dividend_id 去重 + 股本换算，`MetricValueService` 改用 `CalcVersion.DIVIDEND_YIELD_TTM_V2`
- 详情页/市场页展示行为不变（MISSING 语义、亏损状态文案、来源 system_calc 均保留）

**测试**
- `ShareBasisAdjusterTest` 11 用例（四类事件/叠加/同日/窗口/异常输入）
- `DividendYieldCalculatorTest` 12 用例（6%/6.25%/5.45%、窗口边界、状态剔除、去重、10转10 → 1.5 元口径）
- `TargetPriceCalculatorTest` 4 用例（75.00/60.00/50.00/42.86/37.50、无效输入、默认档位）
- `PeTtmCalculatorTest` 11 用例（500亿/50亿=10、600亿→12、400亿→8、零→N_A、负→LOSS、不足四季/不连续/缺上期→N_A）
- 后端全量 72 测试通过

### 文档同步
- `docs/specs/010-home-board/tasks.md`：T3-01~T3-06 标记完成，状态「阶段三完成，阶段四开工」
- `docs/specs/010-home-board/design.md`：§3.3 配置键落地为 `lingkan.board.target-yields`（原 stock.target-yields）；§3.4 补充累计→单季换算公式
- `.code-docs/`：新增 11 篇（4 计算器 + BoardProperties + StockIndicatorService + BoardRowDto + 4 测试文档）、更新 4 篇（MetricValueService/CalcVersion/application/DividendStatusRules）（不入库，见其 CHANGELOG）

---

## [0.5.0] - 2026-10-30

### 新增：分红/财务同步服务与调度（spec 010 阶段二收尾）

**同步服务 `DividendFinancialSyncService`**
- 三种触发：每日 05:30 定时（`DividendFinancialSyncScheduler`，自选股增量）、添加自选成功后异步即时同步（`WatchlistService.add` → `syncAsync`，触发方式 `WATCHLIST_ADD`）、手动接口 `POST /api/v1/sync/dividend-financial`（缺省 stockCodes 同步全部自选股，异步返回 `SyncAcceptVo{jobId,accepted,count,message}`）
- 分红三级匹配去重：`dividend_id` → 「归属年度+除息日」→ 「归属年度+公告日」，支持预案→实施的方案演进原地更新；`implementation_date` 同步永不覆盖（保留人工/兜底值）
- 股本事件（`stock_share_events`）：仅对「已实施 + 除息日存在 + 比例 > 0」的方案生成 BONUS/TRANSFER 事件（口径类 `calculation.DividendStatusRules`）
- 财报按「报告期 + source_code」upsert，值未变不写库（BigDecimal 按 compareTo 判等，忽略标度）
- `data_sync_logs` 留痕（`sync_type = DIVIDEND / FINANCIAL`，RUNNING→SUCCESS/PARTIAL/FAILED）；批次互斥（AtomicBoolean），被拒提交返回 accepted=false
- 配置 `lingkan.dividend-financial-sync.*`（enabled/cron 05:30/timezone/request-interval-ms 500）

**数据源契约变更（MISSING 语义）**
- `DividendProvider`/`FinancialProvider` 契约由「失败或无记录返回空列表」改为「失败返回 null（MISSING）、成功但无记录返回空列表」；东财两个适配器同步修改（非 200/网络/解析异常 → null）
- 同步服务以 null 判定取数失败（计入失败数、保留旧数据），以空列表判定成功无数据

**测试**
- `DividendStatusRulesTest`：已实施/预案/不实施/状态缺失 4 用例
- `DividendFinancialSyncServiceTest`（Mockito）：取数失败不写库、送转事件生成条件、预案→实施原地更新且不覆盖 implementation_date、重复同步不写库、财报 upsert 值未变不更新等 7 用例

### 文档同步
- `docs/specs/010-home-board/tasks.md`：T2-01~T2-08、T4-02、T4-03 标记完成
- `docs/specs/010-home-board/design.md`：§4.3 去重规则细化为三级匹配
- `.code-docs/`：新增 9 篇、更新 6 篇（不入库，见其 CHANGELOG）

---

## [0.4.0] - 2026-10-30

### 新增：首页自选看板与计算引擎（开发中）

**Spec**
- `docs/specs/010-home-board/`：依据用户技术方案 V1.0 落成四件套（spec/design/tasks/acceptance），含 4 项用户裁决（横向滚动表格、移除首页市场概览、TTM 派息日优先、第一版股本换算）
- `docs/specs/009-stock-sync/`：补记全市场股票同步系统 Spec（与已实现代码一致）

**数据库（V5 迁移）**
- `stock_dividends` 新增 `dividend_id`（分红方案唯一标识）、`implementation_date`（实际派息实施日期）及索引
- 新建 `stock_share_events` 股本变动事件表（送股/转增/拆股/并股），支撑历史分红换算当前股本口径
- `metric_definitions.dividend_yield_ttm` 口径说明补充 TTM 窗口与股本换算规则

**数据源适配器（实测验证字段）**
- `EastMoneyDividendProvider`：RPT_SHAREBONUS_DET 解析（每 10 股→每股、送转比例、dividend_id 锚定）；实测无派息日字段（payment/implementation 恒 null，TTM 按除息日兜底）
- `EastMoneyFinancialProvider`：RPT_LICO_FN_CPD 解析（QDATE 报告期、DATEMMDD 类型映射、累计值警示）

**修复**
- `DecimalUtilsTest` 精度断言改为数值相等（compareTo），不再断言标度相等（尾零位数随 MathContext(16) 运算自然变化）

**其他**
- `.gitignore` 新增 `.code-docs/`（自动生成的代码文档，不入库）
- 全市场股票同步系统提交入库（feat(sync)）

### 文档同步
- `docs/specs/010-home-board/*` → v1.0（approved）
- `docs/specs/009-stock-sync/*` → v1.0

---

## [0.3.0] - 2026-10-30

### 新增：Git 版本控制与 GitHub 关联

**仓库**
- 在 `D:\project\lingkan` 执行 `git init`（主分支 `main`），关联远程仓库 `https://github.com/DDYMZL/lingkan.git`（别名 `origin`）
- 首次全量提交并推送至 `origin/main`

**规范**
- `docs/constitution.md`：新增**铁律 13「功能完成即提交」**，新增「六、版本控制」章节，第三节 AI 执行纪律第 5 条加入 commit/push 动作
- `docs/GIT_WORKFLOW.md`：新增，规定功能单元的完成判定、commit message 格式（`type(scope): 中文描述`）、分支策略、提交前自检清单、禁止入库清单、回滚方式、AI 工具附加要求
- `docs/README.md`：目录导航与阅读顺序加入 `GIT_WORKFLOW.md`

**变更**
- `.gitignore` 重写：显式排除 `.tools/`（约 525 MB 本地工具链）、`.backup/`、构建产物、环境变量与密钥文件、日志与 IDE 配置

### 文档同步
- `docs/constitution.md` → v1.1
- `docs/GIT_WORKFLOW.md` → v1.0
- `docs/README.md`、`docs/changes/changelog.md`

---

## [0.2.0] - 2026-10-30

### 变更：前端技术栈由 uni-app 改为微信小程序原生

**决策**
- 按用户要求废弃 uni-app + Vue3 + TS + Vite + Pinia + ECharts 方案，`frontend/` 清空后重建为**微信小程序原生工程**（WXML / WXSS / JavaScript / JSON）
- 不引入任何 npm 依赖、打包器、跨端框架；微信开发者工具直接打开 `frontend/` 即运行，源码即产物

**移除**
- `frontend/src/`（8 个 Vue 页面、18 个 Vue 组件、4 个 Pinia store、TS service 层）
- `package.json` / `vite.config.ts` / `tsconfig.json` / `index.html` / `.env.*` / `tests/` / `node_modules/`
- ADR-0004（pnpm → npm）随之失效，不再需要包管理器

**新增（10 页面 + 3 组件 + 6 service）**

| 层 | 文件 | 说明 |
|---|---|---|
| 入口 | `app.js` / `app.json` / `app.wxss` / `sitemap.json` / `project.config.json` | tabBar 四入口，`lazyCodeLoading: requiredComponents` |
| 配置 | `config/index.js` | `baseUrl` / `apiPrefix` / `timeout` / 环境切换 |
| 工具 | `utils/request.js` | Promise 封装、`X-LingKan-User` 注入、`code===0` 解包、中文错误提示 |
| 工具 | `utils/format.js` | 时间/相对时间/涨跌色/大数/状态标签映射 |
| 工具 | `utils/metrics.js` | 动态指标组装：列表列、分类分组、行情头、来源追溯 |
| 工具 | `utils/user.js` | 本地 user_id 生成与持久化 |
| 服务 | `services/{stock,system,metrics,preference,datasource,watchlist}.js` | 一一对应后端 Controller |
| 组件 | `components/state-tag` | 指标/采集状态标签（OK/MISSING/STALE/ERROR/PENDING） |
| 组件 | `components/empty-state` | 空态占位 |
| 组件 | `components/line-chart` | Canvas 2D 折线图（自研，替代 ECharts） |
| 页面 | `pages/home` | 自选 + 市场概览 + 交易时段 + 数据时间 |
| 页面 | `pages/ranking` | 全市场分页 + 关键词/交易所筛选 + 按指标排序 |
| 页面 | `pages/search` | 搜索并直接加入自选 |
| 页面 | `pages/stock` | 详情：行情 + 分类指标（带来源/时间）+ 历史折线 + 分红 |
| 页面 | `pages/compare` | 单指标多数据源对比 + 切换主数据源 |
| 页面 | `pages/config` | 配置中心 + 数据源健康状态 |
| 页面 | `pages/config-metrics` | 指标启停 / 展示位置 / 精度 / 排序 / 主数据源 |
| 页面 | `pages/datasource` | 数据源启停 + 连通性测试 |
| 页面 | `pages/datasource-logs` | 采集日志 |
| 页面 | `pages/about` | 版本、数据来源、使用边界、免责声明、清缓存 |
| 资源 | `assets/tabbar/*.png` | 8 个 81×81 图标，纯脚本生成（无外部依赖） |

**关键实现约束**
- 页面模板零指标字面量：列与分组全部来自 `/user/metric-preferences` 与 `/metrics/categories`
- `PENDING` 口径指标只渲染「待确认」，不输出任何计算结果
- `available === false` 时只显示空态，不估算、不补值
- 指标展示决策统一收敛在 `utils/metrics.js` + `components/state-tag`

**质量校验**
- 25 个 JS 文件 `node --check` 全部通过
- 全部 JSON 文件可解析；10 个页面的 js/json/wxml/wxss 四件套齐全
- 全部 WXML 标签配对检查通过；`usingComponents` 引用路径全部存在
- 汇总：**0 问题**

**文档同步**
- `docs/constitution.md` 原则 4 技术栈
- `docs/architecture/frontend-architecture.md` 重写
- `docs/architecture/system-architecture.md` 架构图
- `docs/specs/001-project-overview/{design,tasks,acceptance}.md`
- `docs/specs/008-mini-program-ui/{design,tasks}.md` 重写
- `docs/tech-stack-report.md` 前端章节

### 已知问题

| 编号 | 问题 | 状态 |
|---|---|---|
| ISSUE-05 | 微信开发者工具尚未实际打开验证渲染 | 待本地验证 |
| ISSUE-06 | 真机调试需 HTTPS 域名（当前为 localhost） | 待部署 |

---

## [0.1.0] - 2026-10-30

### 新增：项目初始化（Phase 0）

**目录与工程**
- 创建 `D:\project\lingkan`，四个一级目录 `frontend` / `backend` / `docker` / `docs`（严格英文，无中文一级目录）
- 前端：uni-app + Vue 3 + TypeScript(strict) + Vite + Pinia + ECharts + SCSS，目标 `mp-weixin`
- 后端：Spring Boot 4.1.2 + Java 25 + MyBatis-Plus 3.5.18.1 + openGauss JDBC 6.0.6
- 部署：Docker Compose（openGauss 5.0.0 + backend + nginx）+ start/stop/restart/backup/restore 脚本

**SDD 文档**
- `docs/constitution.md`：12 条项目宪法原则
- `docs/specs/001` ~ `008`：8 个功能 Spec（spec / design / tasks / acceptance）
- `docs/architecture/`：系统、前端、后端、数据源、数据库五份架构文档
- `docs/api/api-contract.md`：完整 API 契约
- `docs/database/schema.md`：14 张表的字段级设计
- `docs/tech-stack-report.md`：技术选型报告（含兼容性核实与风险）
- `docs/tasks/implementation-plan.md`：7 阶段实施计划

**后端模块**（86 个 Java 文件）
- `stock` 股票基础信息、列表、搜索、详情
- `market` 市场概览与刷新
- `metrics` 指标定义、用户偏好、指标值解析
- `datasource` 数据源注册、启停、健康检查、适配器（腾讯/东财/新浪 + 5 个占位）
- `dividend` / `financial` 分红与财务数据
- `technical` 技术指标计算器
- `watchlist` 自选股
- `history` 历史序列查询
- `scheduler` 定时采集骨架（默认关闭）
- `system` 数据状态
- `common` 统一响应、异常、CORS、缓存、异步、OpenAPI、请求追踪、用户上下文、迁移器

**前端页面与组件**（24 个 Vue 文件）
- 8 个页面：首页、市场、自选、股票详情、指标配置、数据源配置、数据源对比、我的
- 18 个组件：`MetricValue`（唯一指标渲染点）、`StockRow`/`StockCard`/`StockSearch`、`MetricSelector`/`MetricSort`/`MetricFilter`、`SourceSelector`/`SourceCompare`/`SourceStatus`、`LineChart`/`PeChart`/`DividendChart`、`LkPage`/`LkEmpty` 等
- 4 个 Pinia store、7 个 service、统一 `request.ts`、小程序 canvas 2d 图表封装

**数据库迁移**
- `V1__init_schema.sql`：14 张表 + 唯一约束 + 索引
- `V2__seed_metric_definitions.sql`：预置指标定义（含 6 个 `PENDING` 周线指标）
- `V3__seed_providers_and_bindings.sql`：8 个数据源 + 指标绑定初始方案

### 新增：数据源研究（Phase 1）

- 8 个候选数据源**逐一真实请求验证**，结果记录于 `docs/specs/004-data-source/source-research.md`
- 关键发现：
  - 东方财富 5 个接口全部 HTTP 200 可用（列表/快照/K线/分红/财报）
  - 腾讯财经批量接口可用，字段位置已实测确认，编码为 **GBK**
  - 新浪财经可用但必须带 `Referer`
  - **乐咕乐股全部 404，判定不可用**
  - Tushare 需 Token（业务码 40101）
  - 巨潮仅返回公告索引，无结构化财务字段
- 三源交叉核对 600519：最新价 1392.05、涨跌幅 2.81%、PB 6.93 **三源一致**

### 决策记录

| 编号 | 决策 | 状态 |
|---|---|---|
| ADR-0001 | 不使用 Flyway，改用内置 `DbMigrationRunner` | 待用户确认 |
| ADR-0002 | 乐咕乐股不可用，股息率改为系统计算 | 已采用 |
| ADR-0003 | 接口 PE 口径有歧义，PE-TTM 一律自算 | 已采用 |
| ADR-0004 | pnpm 在 Windows 下符号链接失败，改用 npm | 已采用 |
| ADR-0005 | 不使用数据库外键，改由应用层保证 | 已采用 |

### 修复

- **MyBatis-Plus 分页插件缺失导致编译失败**
  - 现象：`MybatisPlusConfig.java` 报 `找不到符号: 类 PaginationInnerInterceptor`
  - 原因：MyBatis-Plus 3.5.9 起把基于 JsqlParser 的内置拦截器（含 `PaginationInnerInterceptor`）从 `mybatis-plus-extension` 拆分到独立构件
  - 修复：`pom.xml` 增加 `com.baomidou:mybatis-plus-jsqlparser:3.5.18.1`（版本与 starter 严格一致）
  - 验证：`mvn -DskipTests package` **BUILD SUCCESS**，85 个主源文件 + 1 个测试源文件编译通过，产出 `target/lingkan-backend-1.0.0.jar`（36.31 MB，已 repackage 为可执行 jar）

### 构建验证记录

| 项 | 结果 |
|---|---|
| JDK | Temurin 25.0.5+8（`D:\project\lingkan\.tools\jdk-25.0.5+8`） |
| Maven | 3.9.16（项目内 `.tools`） |
| 后端编译 | ✅ BUILD SUCCESS，85 个源文件 |
| 后端可执行包 | ✅ `lingkan-backend-1.0.0.jar` 36.31 MB |
| Spring Boot 版本 | ✅ 4.1.2（parent 解析成功） |
| openGauss 驱动 | ✅ 依赖解析成功（实际连接未验证） |
| 前端编译 | ⏳ 依赖安装受网络影响，尚未完成 |

### 已知问题

| 编号 | 问题 | 状态 |
|---|---|---|
| T-TECH-01 | `DataSourceService` 跨模块注入 `MetricSourceBindingMapper` | 待重构 |
| T-TECH-02 | 适配器限流为简单 sleep，非线程安全令牌桶 | 待改进 |
| T-TECH-03 | 缺少集成测试（需 openGauss） | 待补 |
| ISSUE-01 | 前端依赖安装受网络影响，尚未完成 | 进行中 |
| ISSUE-02 | 后端首次构建依赖下载中 | 进行中 |
| ISSUE-03 | Docker 守护进程未启动，openGauss 未连通 | 阻塞 |
| ISSUE-04 | 微信小程序尚未编译验证 | 阻塞 |

### 待用户确认

| 编号 | 问题 | 影响 |
|---|---|---|
| Q7-1~Q7-8 | 周30/周中/周60/周下/周上/带内位置的计算公式与参数 | 6 个指标无法实现 |
| Q-7.2 | 「近3天创60日新低」的 5 项口径（价格字段/窗口含当日/复权/3天定义/OR或连续） | 当前按默认值实现，标记 `review` |
| Q7-9 | 指标口径变更时覆盖旧值还是保留多版本行 | 影响 `stock_daily_metrics` 设计 |
| R1 | 是否接受 Flyway 替换为内置迁移器 | 偏离原技术文档 |
| Q4-2 | 是否申请 Tushare Token | 影响备用数据源可用性 |

### 未实现（明确排除在第一版外）

- 周30 / 周中 / 周60 / 周下 / 周上 / 带内位置（公式待确认）
- PC 管理后台 / 独立 Web 管理系统（项目边界禁止）
- 港股 / 美股 / 基金 / 期货 / 交易功能（项目边界禁止）
- 多用户注册登录（保留 `user_id` 维度）
- Python AKShare 采集容器（目录预留）
- 巨潮财报 PDF 解析
- K 线图（仅折线图）
- Redis（Caffeine 代替）

---

## 模板（后续变更请按此格式追加）

```markdown
## [版本号] - YYYY-MM-DD

### 新增
### 变更
### 修复
### 移除
### 口径变更（重要：需同步 calculation_version）
### 文档同步
```
