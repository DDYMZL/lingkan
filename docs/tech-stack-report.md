# LingKan 技术选型报告

> 版本：V1.0
> 日期：2026-10-30
> 状态：**待用户确认**
> 对应文档章节：V1.0 技术实现文档「十六、AI 编程工具首次执行指令 · 第五步」

本报告记录本项目实际选用的技术版本、兼容性核实过程与结论。所有版本号均来自真实构件仓库查询与实际构建验证，不采用推测值。

---

## 一、环境检查结果

| 组件 | 要求 | 本机实际情况 | 结论 |
|---|---|---|---|
| JDK | Java 25 LTS | 系统未预装 Java 25；已落地 `D:\project\lingkan\.tools\jdk-25.0.5+8`（Eclipse Temurin / Adoptium OpenJDK 25.0.5） | 已补齐 |
| Maven | 3.9.x | 系统未预装；已落地 `D:\project\lingkan\.tools\apache-maven-3.9.16` | 已补齐 |
| Node.js | 18+ | 22.22.2 | 满足 |
| npm | — | 随 Node 提供 | 满足 |
| pnpm | 建议 | 已安装但 Windows 下创建符号链接受限（EPERM），本项目改用 npm 安装 | 改用 npm |
| Git | — | 已安装 | 满足 |
| Docker | Docker Compose | Docker CLI 已安装；Docker Desktop 守护进程未运行 | 配置已就绪，运行需启动 Docker Desktop |
| 微信开发者工具 | 编译/预览小程序 | 已安装于 `D:\Programs\小程序开发者工具` | 满足 |

> 说明：JDK 与 Maven 安装在项目内 `.tools/` 目录（已在 `.gitignore` 中排除），不污染系统环境变量，便于复现。

---

## 二、后端技术栈

| 组件 | 选定版本 | 核实方式 | 结论 |
|---|---|---|---|
| Java | 25（25.0.5） | `javac -version` 实测 | ✅ |
| Spring Boot | **4.1.2** | Maven Central `spring-boot-starter-parent` 版本目录核实，非 RC / 非 SNAPSHOT | ✅ |
| 构建工具 | Maven 3.9.16 | 实测构建 | ✅ |
| ORM | **MyBatis-Plus 3.5.18.1**，starter 为 `mybatis-plus-spring-boot4-starter` | Maven Central 核实存在 Spring Boot 4 专用 starter | ✅ |
| openGauss 驱动 | **org.opengauss:opengauss-jdbc 6.0.6** | Maven Central 核实 | ✅（连接验证见下文） |
| API 文档 | springdoc-openapi-starter-webmvc-ui **3.2.0** | Maven Central 核实 | ✅ |
| 参数校验 | spring-boot-starter-validation（Jakarta Validation） | 由 Spring Boot BOM 管理 | ✅ |
| JSON | Jackson | 由 Spring Boot BOM 管理 | ✅ |
| 缓存 | Caffeine（`spring-boot-starter-cache` + caffeine） | 由 Spring Boot BOM 管理 | ✅ |
| 定时任务 | Spring Scheduling | 内置 | ✅ |
| 日志 | SLF4J + Logback | 内置 | ✅ |
| 测试 | JUnit 5 / JUnit 6 BOM + Mockito 5.23.0 | 由 Spring Boot BOM 管理 | ✅ |
| Lombok | 1.18.48 | 显式指定，兼容 JDK 25 | ✅ |

### 2.1 关键兼容性说明

**Java 25 + Spring Boot 4.1.2**
Spring Boot 4.x 基线为 Spring Framework 7，要求 JDK 17+，官方支持 JDK 25。本项目 `maven.compiler.release=25`，编译插件显式配置 `annotationProcessorPaths` 以适配 JDK 25 下的 Lombok 注解处理。

**MyBatis-Plus 与 Spring Boot 4**
MyBatis-Plus 传统 starter（`mybatis-plus-boot-starter` / `mybatis-plus-spring-boot3-starter`）依赖 Spring Boot 3 的自动配置元数据，在 Spring Boot 4 下不可用。因此**必须**使用 `mybatis-plus-spring-boot4-starter`。这是本项目最容易踩坑的一点，已在本报告明确记录。

**openGauss JDBC**
选用官方 `org.opengauss:opengauss-jdbc`。该驱动基于 PostgreSQL 协议实现，连接串形如：

```text
jdbc:opengauss://host:port/dbname
```

驱动类为 `org.opengauss.Driver`。**注意**：不使用 `org.postgresql.Driver`，避免方言与驱动行为差异。

**Flyway 的处理（偏离原文档，需确认）**
原技术文档要求使用 Flyway 且「需验证 openGauss 兼容性」。核实结论：Flyway 社区版对 openGauss 没有官方 database type 支持，直接用 PostgreSQL 模式连接 openGauss 存在版本识别失败风险。

本项目采用的处理方式：
1. **第一版使用内置轻量迁移器** `DbMigrationRunner`：按文件名顺序执行 `db/migration/V*.sql`，并在 `lingkan_schema_version` 表中记录已执行版本，具备幂等性与版本追溯。
2. 迁移脚本本身严格采用 openGauss 兼容 SQL，不依赖 Flyway 专有语法。
3. 后续如确认 Flyway 在目标 openGauss 版本可用，可平滑替换（脚本命名已按 Flyway 规范 `V{n}__{desc}.sql`）。

> ⚠️ 这是对原文档的一处技术偏离，**需要用户确认**。

---

## 三、前端技术栈

> v1.1 变更：按用户要求，前端由 uni-app 方案改为 **微信小程序原生开发**，不再引入任何 npm 依赖与构建工具。

| 项 | 选定方案 | 结论 |
|---|---|---|
| 框架 | 微信小程序原生（WXML + WXSS + JavaScript + JSON） | ✅ |
| 基础库 | 3.5.5（`project.config.json` 中声明） | ✅ |
| 转译/构建 | 无（微信开发者工具直接运行源码） | ✅ |
| 依赖 | 无 npm 依赖，无 `node_modules` | ✅ |
| 样式 | WXSS + CSS 变量（无 SCSS 预编译） | ✅ |
| 图表 | 原生 Canvas 2D，自研 `components/line-chart` | ✅ |
| 状态共享 | `app.globalData` + `wx.storage`（不引入状态库） | ✅ |
| 请求 | `utils/request.js` 封装 `wx.request`（Promise 化） | ✅ |
| 包体积 | 主包仅源码 + 8 个 tabBar 图标（< 200KB） | ✅ |

### 3.1 为何不用 uni-app

| 维度 | uni-app | 原生 |
|---|---|---|
| 构建链路 | Vite + Vue 编译器 + 平台插件，链路长 | 无 |
| 运行时开销 | 需携带 Vue 运行时 | 无 |
| 调试 | 需排查「源码 → 产物」映射 | 所见即所得 |
| 包体积 | ECharts + Vue 运行时易触发 2MB 主包限制 | 极小 |
| 适用场景 | 多端复用（App/H5/小程序） | 仅微信小程序 |

本项目只面向微信小程序，没有多端诉求，原生化收益大于成本。

### 3.2 图表方案

原文档要求「不得假设普通 Web ECharts 初始化代码能运行在小程序」。

v1.1 实际方案：直接用原生 `<canvas type="2d">` + `CanvasRenderingContext2D` 自绘折线图（约 150 行），处理了 `dpr` 缩放、渐变面积填充、最高/最低标注、起止日期标注。**彻底移除了 ECharts**，同时消除了主包体积风险。

**当前状态**：组件代码已按 Canvas 2D 规范实现；微信开发者工具内的实际渲染验证需本地打开工具确认，列为待验收。

### 3.3 不使用 PC 管理后台

前端目录中不存在 `admin` / `admin-web` / `dashboard-admin` / `web-admin` 等目录。全部页面位于 `frontend/pages/` 下，共 10 个移动端页面，其中 4 个为 tabBar 入口（自选 / 全市场 / 配置 / 我的）。

---

## 四、数据库与部署

| 组件 | 选定版本 | 说明 |
|---|---|---|
| 数据库 | openGauss 5.0.0（容器镜像 `enmotech/opengauss:5.0.0`） | 数据卷持久化，健康检查 `gsql` 探活 |
| 容器编排 | Docker Compose | `docker/compose.yml` |
| 反向代理 | Nginx（alpine） | 反代后端 `/api`，预留小程序域名校验文件目录 |
| 缓存 | Caffeine（进程内） | Redis 预留，第一版不部署 |

### 4.1 Docker 镜像可用性

- `enmotech/opengauss:5.0.0`：开源社区维护的 openGauss 官方推荐镜像，支持 `GS_PASSWORD` 环境变量初始化。
- `eclipse-temurin:25-jre`：作为后端运行时基础镜像（与本地 JDK 25 一致）。
- `nginx:alpine`：通用可用。

> 当前 Docker 守护进程未启动，镜像拉取与容器启动**尚未实测**。`docker compose config` 语法校验已完成。

---

## 五、版本锁定清单（可直接复现）

```text
JDK          25.0.5+8            (Adoptium Temurin)
Maven        3.9.16
Spring Boot  4.1.2
MyBatis-Plus 3.5.18.1            (mybatis-plus-spring-boot4-starter)
openGauss    6.0.6               (org.opengauss:opengauss-jdbc)
springdoc    3.2.0
Lombok       1.18.48
Node.js      22.22.2            (仅后端构建/工具链，前端不再需要)
前端         微信小程序原生       (WXML/WXSS/JS，无 npm 依赖)
openGauss    5.0.0               (docker image enmotech/opengauss:5.0.0)
```

---

## 六、已知风险与待确认事项

| 编号 | 事项 | 影响 | 建议 |
|---|---|---|---|
| R1 | Flyway 未采用，改为内置迁移器 | 偏离原文档 | 请确认是否接受；不接受则需先做 Flyway × openGauss 专项验证 |
| R2 | ~~pnpm 在 Windows 下创建符号链接失败，改用 npm~~ | 已随 uni-app 方案废弃而消失 | 无需处理 |
| R3 | Docker 守护进程未启动 | 容器化部署与 openGauss 实际连接未验证 | 需启动 Docker Desktop 后补验证 |
| R4 | ~~ECharts 小程序端渲染未实测~~ → 已移除 ECharts | 改为自研 Canvas 2D 折线图，风险消除 | 仅需验证折线渲染 |
| R5 | 小程序真机调试需 HTTPS 域名 | 当前 `baseUrl` 为 `http://localhost:8080`，仅开发者工具可用 | 部署后替换 `config/index.js` 的 `prod.baseUrl` 并在小程序后台配置合法域名 |
| R5 | openGauss JDBC 6.0.6 与 openGauss 5.0.0 服务端的实际握手未验证 | 可能出现协议/认证差异 | 需 Docker 起来后立即验证 |
| R6 | 微信小程序需 HTTPS 合法域名备案，本地开发需勾选「不校验合法域名」 | 联调方式 | 开发阶段用本地 IP + 关闭校验 |

---

## 七、结论

技术选型在**版本真实性**上已全部通过仓库核实，无 SNAPSHOT / RC / M 版本。核心风险集中在三处：Flyway 替换（R1）、Docker 未启动导致的数据库实连未验证（R3/R5）、小程序图表未实测（R4）。

建议下一步：启动 Docker → 起 openGauss → 跑后端 → 用微信开发者工具编译小程序 → 端到端验证后再进入 Phase 2 业务开发。
