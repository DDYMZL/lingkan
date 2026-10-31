# 010-首页自选看板与计算引擎 · 任务清单

> 版本：v1.2　日期：2026-10-31
> 状态：阶段一~六B 已完成（v1.1）；阶段六C（v1.2 列扩展）进行中

## 阶段一：现状分析（已完成）

- [x] T1-01 检查现有小程序首页 → 卡片列表 + 市场概览（结论见 spec.md 差异表）
- [x] T1-02 检查后端目录与计算能力 → MetricValueService 已有 PE/股息率雏形
- [x] T1-03 检查数据库表 → 四张业务表已存在，缺 dividend_id/implementation_date 与股本事件表
- [x] T1-04 检查数据源适配器 → 腾讯/东财快照已实现，分红/财报适配器缺失
- [x] T1-05 检查同步任务 → 全市场同步（未提交），无分红/财务同步
- [x] T1-06 确认 Redis/WebSocket → 均无，Caffeine 替代（方案允许）
- [x] T1-07 输出差异 → spec.md 第五节 + 4 项用户裁决

## 阶段二：数据库与数据采集

- [x] T2-01 V5 迁移：stock_dividends 加 dividend_id/implementation_date + 索引；新建 stock_share_events；修正 dividend_yield_ttm 口径说明
- [x] T2-02 实体/Mapper 同步：StockDividend、StockShareEvent（新）
- [x] T2-03 真实请求验证东财分红接口 RPT_SHAREBONUS_DET 字段（记录到 source-research.md 附录）
- [x] T2-04 真实请求验证东财财报接口 RPT_LICO_FN_CPD 字段
- [x] T2-05 EastMoneyDividendProvider（含送转事件提取）
- [x] T2-06 EastMoneyFinancialProvider
- [x] T2-07 DividendFinancialSyncService（自选增量 + 添加自选触发 + 手动接口）
- [x] T2-08 分红/财务同步单元测试（样本报文解析 + 去重 + 状态识别）

## 阶段三：计算引擎

- [x] T3-01 ShareBasisAdjuster + 单元测试
- [x] T3-02 DividendYieldCalculator（派息日优先 + 股本换算）+ 单元测试
- [x] T3-03 TargetPriceCalculator（4%~8% 可配置）+ 单元测试
- [x] T3-04 PeTtmCalculator（亏损/零/缺失/期号连续性）+ 单元测试
- [x] T3-05 StockIndicatorService + BoardRowDto
- [x] T3-06 MetricValueService 重构为复用计算器（保持现有行为不变）

## 阶段四：后端接口

- [x] T4-01 GET /api/v1/watchlist/board 看板聚合接口
- [x] T4-02 POST /api/v1/sync/dividend-financial 手动同步接口
- [x] T4-03 添加自选后异步触发分红/财务即时同步（WatchlistService 联动）
- [x] T4-04 分红接口返回补充 dividend_id/implementation_date（现有 StockDividend 实体已直接返回两字段，无需改代码）

## 阶段五：行情刷新

- [x] T5-01 QuoteSyncScheduler 实现 15s 轮询（仅交易时段、可配置、批量、共享缓存）
- [x] T5-02 轮询结果 → Caffeine 缓存 → 变化才落库 → 触发计算 + 推送
- [x] T5-03 新增 websocket starter 依赖 + WebSocketConfig
- [x] T5-04 QuotePushService：/ws/stocks/favorites，STOCK_QUOTE_UPDATE 消息（含重算的股息率/PE）
- [x] T5-05 非交易时段/接口失败降级（保留最后行情、标记 STALE）

## 阶段六：小程序首页

- [x] T6-01 services/board.js + WS 连接管理
- [x] T6-02 首页改版：顶栏 + 固定表头 + 固定首列 + 横向滚动（scroll-x 同步）
- [x] T6-03 15 列表格渲染 + 颜色规则（涨红跌绿/股息率强调/目标价达标高亮/亏损）
- [x] T6-04 WS 行级更新（路径 setData）+ 断线重连 + 回前台重拉快照 + 下拉刷新
- [x] T6-05 移除市场概览与全市场入口；空状态按方案 7.4
- [x] T6-06 添加自选交互（沿用搜索页 → 添加 → 后端即时同步 → 返回首页刷新看板）

## 阶段六 B：首页交互精简（v1.1，用户已确认）

- [x] T6B-01 默认五列适配手机屏宽，移除横向滚动与 sticky 首列
- [x] T6B-02 九档目标价改为行内 3×3 展开区，展开状态独立维护
- [x] T6B-03 保留 WS 行级更新与目标价达标高亮，推送不影响展开状态

## 阶段七：联调验收与收尾

- [x] T7-01 验收清单逐项核对（自动化项通过；开发者工具布局、真实 WS 与数据源联调缺口见 acceptance/changelog）
- [x] T7-02 更新 008/005 Spec、010 状态保持 approved、changelog
- [x] T7-03 后端全量构建 + 83 测试通过；前端 JS/JSON/WXML 静态检查与 Node 运行时桩通过
- [ ] T7-04 commit + push origin/main（铁律 13），报告 commit hash 与推送结果

## 阶段六 C：v1.2 列扩展（2026-10-31 用户裁决 ⑥~⑩）

- [x] T6C-01 Spec 先行：010 spec/design/acceptance/tasks 升 v1.2；007 Q7-2/3/4/6/7/8 记录确认结论；008 §4/§5.1 恢复横滚 + 十列
- [ ] T6C-02 行业列：`BoardRowDto.industry` + `StockIndicatorService` 填充 `stocks.industry`
- [ ] T6C-03 目标价档位收窄：`application.yml` 与 `TargetPriceCalculator.DEFAULT_TARGET_YIELDS` 改六档，前端档位数组与 3×2 展开区同步，更新既有单测
- [ ] T6C-04 V6 迁移：`stock_weekly_quotes` 表 + `week_120` 新增登记 + `week_60/week_upper/week_middle/week_lower` 转 READY 写入公式
- [ ] T6C-05 `WeeklyKlineProvider` + `WeeklyBar` DTO + `EastMoneyWeeklyKlineProvider`（klt=102/fqt=1，真实报文单测）
- [ ] T6C-06 `StockWeeklyQuote` 实体 + Mapper
- [ ] T6C-07 `WeeklyIndicatorCalculator`（MA60/MA120 + 周线布林 20/2 + 本周实时价覆盖 + 非正收盘价防护）+ 单测
- [ ] T6C-08 `WeeklyKlineSyncService` + 调度（交易日 60 分钟）+ 手动接口 + 添加自选异步触发 + `data_sync_logs`（WEEKLY_KLINE）
- [ ] T6C-09 看板与 WS 接入：批量读周线（无 N+1）、`BoardRowDto` 五个周线字段 + `weeklyStatus`/`weeklyDataTime`、`QuoteUpdateMessage` 同步下发
- [ ] T6C-10 前端横向滚动表格：首列 sticky、表头 sticky、十列对齐、周线列渲染与 WS 行级更新、缺失 `--`
- [ ] T6C-11 验证：后端全量测试通过；重启后 V6 生效；手动触发周线同步并 curl 核对至少一只股票的 MA60 与东财原始周线手算一致；开发者工具 automator 验证布局与展开
- [ ] T6C-12 文档同步：changelog、`docs/database/schema.md`、`source-research.md`（周 K 实测 + 前复权负价坑）、`frontend/README.md`、`.code-docs`
- [ ] T6C-13 两次 commit + push origin/main，报告 hash 与推送结果
