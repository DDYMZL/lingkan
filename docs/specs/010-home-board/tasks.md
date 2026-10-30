# 010-首页自选看板与计算引擎 · 任务清单

> 版本：v1.0　日期：2026-10-30
> 状态：进行中（阶段三完成，阶段四开工）

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

- [ ] T4-01 GET /api/v1/watchlist/board 看板聚合接口
- [x] T4-02 POST /api/v1/sync/dividend-financial 手动同步接口
- [x] T4-03 添加自选后异步触发分红/财务即时同步（WatchlistService 联动）
- [ ] T4-04 分红接口返回补充 dividend_id/implementation_date

## 阶段五：行情刷新

- [ ] T5-01 QuoteSyncScheduler 实现 15s 轮询（仅交易时段、可配置、批量、共享缓存）
- [ ] T5-02 轮询结果 → Caffeine 缓存 → 变化才落库 → 触发计算 + 推送
- [ ] T5-03 新增 websocket starter 依赖 + WebSocketConfig
- [ ] T5-04 QuotePushService：/ws/stocks/favorites，STOCK_QUOTE_UPDATE 消息（含重算的股息率/PE）
- [ ] T5-05 非交易时段/接口失败降级（保留最后行情、标记 STALE）

## 阶段六：小程序首页

- [ ] T6-01 services/board.js + WS 连接管理
- [ ] T6-02 首页改版：顶栏 + 固定表头 + 固定首列 + 横向滚动（scroll-x 同步）
- [ ] T6-03 15 列表格渲染 + 颜色规则（涨红跌绿/股息率强调/目标价达标高亮/亏损）
- [ ] T6-04 WS 行级更新（路径 setData）+ 断线重连 + 回前台重拉快照 + 下拉刷新
- [ ] T6-05 移除市场概览与全市场入口；空状态按方案 7.4
- [ ] T6-06 添加自选交互（搜索弹窗 → 添加 → 即时同步 → 刷新看板）

## 阶段七：联调验收与收尾

- [ ] T7-01 验收清单逐项核对（spec 验收标准）
- [ ] T7-02 更新 008/005 Spec、010 状态改 approved、changelog
- [ ] T7-03 后端全量构建 + 测试通过
- [ ] T7-04 commit + push origin/main（铁律 13），报告 commit hash 与推送结果
