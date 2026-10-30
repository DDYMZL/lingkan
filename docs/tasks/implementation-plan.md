# LingKan 实施计划

> 版本：v1.0
> 日期：2026-10-30
> 对应原文档「十五、分阶段开发计划」

## 阶段总览

| Phase | 内容 | 状态 | 阻塞项 |
|---|---|---|---|
| 0 | 项目初始化 | 🟡 基本完成 | 依赖安装受网络影响 |
| 1 | 数据源研究 | ✅ 完成 | — |
| 2 | 指标中心 | 🟡 骨架完成 | 待联调 |
| 3 | 数据源中心 | 🟡 骨架完成 | 待联调 |
| 4 | 股票数据中心 | 🟡 骨架完成 | 待采集验证 |
| 5 | 小程序页面 | 🟡 代码完成 | 待编译验证 |
| 6 | 历史与技术指标 | 🟠 部分完成 | **周线公式待确认** |
| 7 | 部署与验收 | 🔴 未开始 | Docker 未启动 |

---

## Phase 0：项目初始化

### 已完成

- [x] `D:\project\lingkan` 与四个一级目录 `frontend` / `backend` / `docker` / `docs`
- [x] Git 仓库与 `.gitignore`
- [x] 前端 uni-app + Vue3 + TS + Vite 工程（24 个 Vue 文件、8 页面、18 组件）
- [x] 后端 Spring Boot 4.1.2 + Java 25 工程（86 个 Java 文件）
- [x] Docker Compose（openGauss + backend + nginx）+ 5 个运维脚本
- [x] SDD 文档体系（constitution + 8 个 Spec + architecture + api + database）
- [x] JDK 25 与 Maven 落地到 `.tools/`

### 未完成

- [ ] **前端依赖安装**（npm，网络慢，已换 npmmirror + `--prefer-offline` 重试）
- [x] **后端 Maven 首次构建** → BUILD SUCCESS，85 源文件，产出 `lingkan-backend-1.0.0.jar`（36.31 MB）
- [ ] **前端首次 `build:mp-weixin` 编译通过**
- [ ] **后端首次启动 + openGauss 连接**

### 验收标准

| 项 | 标准 | 当前 |
|---|---|---|
| 前端 | `npm run build:mp-weixin` 退出码 0，产出 `dist/build/mp-weixin/app.json` | ⏳ 待依赖 |
| 后端 | `java -jar target/lingkan-backend-1.0.0.jar` 启动成功，`/actuator/health` 返回 UP | 🟡 打包成功，启动待数据库 |
| 数据库 | `gsql -c "select 1"` 成功，V1~V3 迁移执行完成 | ⏳ 待 Docker |

### Phase 0 构建期修复记录

| 问题 | 修复 |
|---|---|
| `PaginationInnerInterceptor` 找不到符号 | MyBatis-Plus 3.5.9+ 拆分了 JsqlParser 拦截器，补 `mybatis-plus-jsqlparser:3.5.18.1` |

---

## Phase 1：数据源研究

### 已完成（全部实测）

- [x] 东方财富 5 个接口实测（列表/快照/K线/分红/财报）
- [x] 腾讯财经批量行情实测 + 字段位置确认 + GBK 编码确认
- [x] 新浪财经实测 + Referer 要求确认
- [x] 巨潮资讯公告接口实测
- [x] 乐咕乐股实测（404，判定不可用）
- [x] Tushare 实测（需 Token）
- [x] AKShare / BaoStock 可达性确认
- [x] **三源交叉核对**：600519 最新价 1392.05、涨跌幅 2.81%、PB 6.93 三源一致
- [x] 输出 `docs/specs/004-data-source/source-research.md`

### 关键结论

1. 可稳定支撑第一版的是**东方财富 + 腾讯财经**。
2. 乐咕乐股不可用 → **股息率必须由系统计算**（这反而更符合可追溯原则）。
3. 接口 PE 口径有歧义 → **PE-TTM 一律自算**，不用接口值。
4. 巨潮只有公告索引，无结构化财务字段 → 第一版不用。

---

## Phase 2：指标中心

- [x] `metric_definitions` / `metric_source_bindings` / `user_metric_preferences` 建表
- [x] 指标元数据 DTO 与分类树
- [x] 指标偏好读写 API（含 reset）
- [x] 前端指标配置页（勾选、排序、精度、数据源）
- [x] 动态指标列渲染机制（`MetricValue.vue` 单一渲染点）
- [ ] 指标定义增删接口（第一版不支持，需求待确认）
- [ ] 联调验证

---

## Phase 3：数据源中心

- [x] 数据源注册表 + 8 个内置源
- [x] 3 个真实适配器（腾讯/东财/新浪）+ 5 个占位
- [x] 启停、真实连通性测试、健康状态回写
- [x] 指标×数据源绑定与优先级
- [x] 多源对比接口
- [x] 前端数据源配置页 + 对比页
- [ ] 适配器限流改为令牌桶（T-TECH-02）
- [ ] 降级链完整标注（`degradedFrom`）
- [ ] 采集日志由 scheduler 实际写入

---

## Phase 4：股票数据中心

- [x] 股票列表/搜索/详情/行情接口
- [x] PE-TTM 计算（含亏损处理）
- [x] 股息率 TTM 计算框架
- [x] 利润同比计算（含 4 类异常分支）
- [x] 日线/K线适配器方法
- [x] 分红记录接口
- [ ] **首次全市场股票列表采集入库**
- [ ] **首次行情采集入库**
- [ ] 每日快照任务实测

**阻塞**：需 openGauss 容器运行。

---

## Phase 5：小程序页面

- [x] 首页、市场、自选、详情、指标配置、数据源配置、数据源对比、我的（8 页）
- [x] 通用组件（18 个）
- [ ] **编译验证**
- [ ] 微信开发者工具预览
- [ ] TabBar 图标确认

---

## Phase 6：历史与技术指标

- [x] 历史日线/指标表
- [x] 历史序列查询接口
- [x] `new_low_60d_3d` 计算器（按默认口径 v1）
- [x] 60 日高低、N 日涨跌
- [x] 趋势图组件
- [ ] 历史口径确认（**Q7-1 ~ Q7-8 阻塞**）
- [ ] **周30/周中/周60/周下/周上/带内位置 —— 禁止实现，等公式确认**
- [ ] 历史快照积累

---

## Phase 7：部署与验收

- [x] compose.yml / nginx 配置 / 5 个运维脚本
- [x] 健康检查配置
- [ ] 实际 `docker compose up`
- [ ] openGauss 初始化与迁移
- [ ] 后端容器启动
- [ ] 小程序构建与真机预览
- [ ] 端到端验收

---

## 当前阻塞清单（按优先级）

| 优先级 | 阻塞项 | 解决方式 |
|---|---|---|
| P0 | 前端依赖未装完 | 等待 / 换源重试 |
| P0 | 后端依赖未下完 | 等待 |
| P1 | Docker 守护进程未启动 | 用户启动 Docker Desktop |
| P1 | openGauss 未连通 | 依赖 P1 |
| P2 | 周线指标公式未确认 | **需要用户答复 Q7-1 ~ Q7-8** |
| P2 | `new_low_60d_3d` 口径未确认 | **需要用户答复 5 个口径问题** |
| P3 | Flyway 替换是否接受 | **需要用户确认 R1** |
| P3 | Tushare Token | 需要用户申请 |

---

## 下一步建议

1. 等依赖装完 → 编译前后端 → 启动 Docker → 端到端跑通。
2. 用户确认周线指标公式与 60 日新低口径。
3. 确认后再进入 Phase 2/3 的联调与补测。
