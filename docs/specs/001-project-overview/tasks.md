# Spec 001 — 任务

状态图例：`[x]` 已完成　`[~]` 进行中　`[ ]` 未开始　`[!]` 阻塞（等确认）

## Phase 0：项目初始化

- [x] 创建 `D:\project\lingkan` 与四个一级目录
- [x] 初始化 Git 仓库
- [x] 前端微信小程序原生工程（WXML/WXSS/JS；10 页面 + 3 组件，无构建工具）
- [x] 后端 Spring Boot 4.1.x 工程（Java 25 + MyBatis-Plus + openGauss）
- [x] Docker Compose（openGauss / backend / nginx）
- [x] SDD 文档骨架（constitution + 8 个 Spec + 架构 + 契约）
- [!] 前端在微信开发者工具中运行验证（需本地打开工具并勾选「不校验合法域名」）
- [!] 后端 `mvn package` 编译验证（JDK 25 / Maven 安装中）
- [!] 后端启动 + openGauss 连接验证（需 Docker Desktop 运行）

## Phase 1：数据源研究

- [x] 东方财富（5 个接口实测通过）
- [x] 腾讯财经（批量行情实测通过）
- [x] 新浪财经（需 Referer，实测通过）
- [x] 巨潮资讯（公告接口实测通过，非结构化）
- [x] 乐咕乐股（实测 404，不可用）
- [x] Tushare（可达，需 Token）
- [x] AKShare（Python 库，需采集容器）
- [x] BaoStock（Python SDK，需采集容器）
- [x] 输出 `source-research.md` 与推荐绑定方案

## Phase 2：指标中心

- [x] 指标库表结构 + 74 项指标种子数据
- [x] 未确认指标标记 PENDING（周30/周中/周60/周下/周上/带内位置/近3天创60日新低）
- [x] 用户指标偏好 CRUD + 默认值初始化
- [ ] 指标计算的单元测试扩展（PE、股息率、同比的完整用例）
- [!] 待确认公式（Q1–Q4）确认后实现 technical 计算

## Phase 3：数据源中心

- [x] `StockDataProvider` / `QuoteProvider` SPI
- [x] 东方财富、腾讯、新浪适配器
- [x] 数据源注册表、健康检查、启停、日志接口
- [ ] 采集日志实际写入（待开启同步任务）
- [ ] AKShare Python 采集容器

## Phase 4：股票数据中心

- [ ] 全市场股票基础信息首次灌入（约 5000+ 只）
- [x] 实时行情按需拉取 + 缓存 + 落库
- [ ] 行情定时刷新（默认关闭）
- [ ] 财报数据同步（东方财富 RPT_LICO_FN_CPD）
- [ ] 分红数据同步（东方财富 RPT_SHAREBONUS_DET）
- [ ] 历史日线同步

## Phase 5：小程序页面

- [x] 首页（概览 + 自选 + 排行）
- [x] 市场页（搜索 + 筛选 + 排序 + 加载更多）
- [x] 自选页
- [x] 详情页（行情 + 分组指标 + 三张图表 + 分红）
- [x] 指标配置页
- [x] 数据源配置页
- [x] 数据源对比页
- [x] 我的页
- [ ] 微信开发者工具真机联调

## Phase 6：历史与技术指标

- [ ] 每日指标快照任务
- [ ] 历史走势图联调
- [ ] 60 日新低/新高计算（口径确认后）
- [ ] 周线指标（公式确认后）
- [ ] 交易日历

## Phase 7：部署与验收

- [ ] Docker 镜像构建
- [ ] 小程序 HTTPS 域名配置
- [ ] 端到端验收
