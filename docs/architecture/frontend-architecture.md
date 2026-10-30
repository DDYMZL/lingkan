# 前端架构（微信小程序 · 原生）

> 版本：v1.1
> 日期：2026-10-30
> 变更：由 uni-app + Vue3 + TS 方案改为 **微信小程序原生开发**（WXML / WXSS / JS / JSON），不再引入任何构建工具与 UI 框架。

## 一、技术栈

- 微信小程序原生框架（WXML + WXSS + JavaScript + JSON）
- 基础库 3.5.x，`lazyCodeLoading: requiredComponents`
- 无 npm 依赖、无打包器、无 TypeScript 编译步骤 —— 开发者工具直接打开 `frontend/` 即可运行
- 图表：原生 Canvas 2D（自研极简折线图组件，不引入 ECharts）

选型理由：uni-app 方案会引入 Vite + Vue 运行时 + 打包链路，小程序包体积与调试成本都更高；本项目只有 10 个页面、交互相对朴素，原生开发更直接，也彻底消除了「编译产物与源码不一致」的排查成本。

## 二、目录结构

```text
frontend/                        # ← 微信开发者工具的项目根目录
├── app.js                       # 全局逻辑（仅少量跨页状态）
├── app.json                     # 页面注册 / window / tabBar / 分包配置
├── app.wxss                     # 设计令牌 + 通用类
├── sitemap.json
├── project.config.json
├── project.private.config.json
├── config/
│   └── index.js                 # 环境配置：baseUrl / apiPrefix / timeout
├── utils/
│   ├── request.js               # 统一请求封装（Promise）
│   ├── format.js                # 时间 / 数值 / 状态标签 / 涨跌色
│   ├── metrics.js               # 指标展示组装（列表列、分类分组、行情头）
│   └── user.js                  # 本地 user_id（X-LingKan-User）
├── services/
│   ├── stock.js                 # /stocks/**
│   ├── system.js                # /market/**、/system/**
│   ├── metrics.js               # /metrics/**
│   ├── preference.js            # /user/metric-preferences
│   ├── datasource.js            # /data-sources/**
│   └── watchlist.js             # /watchlist/**
├── components/
│   ├── state-tag/               # 指标/采集状态标签
│   ├── empty-state/             # 空态占位
│   └── line-chart/              # Canvas 2D 折线图
├── pages/
│   ├── home/                    # 自选 + 市场概览（tab）
│   ├── ranking/                 # 全市场筛选（tab）
│   ├── config/                  # 配置中心（tab）
│   ├── about/                   # 我的 / 免责（tab）
│   ├── search/                  # 搜索
│   ├── stock/                   # 个股详情
│   ├── compare/                 # 单指标多数据源对比
│   ├── config-metrics/          # 指标展示配置
│   ├── datasource/              # 数据源管理
│   └── datasource-logs/         # 采集日志
└── assets/tabbar/               # 8 个 tabBar 图标（脚本生成）
```

## 三、分层约束

```text
pages ──► services ──► utils/request.js ──► wx.request
  │
  └──► utils/metrics.js（纯函数组装展示结构）
```

**硬规则**：

1. `pages/` 不得直接调用 `wx.request`。
2. `services/` 只做接口映射，不做任何业务计算。
3. `utils/` 全部为纯函数，不依赖页面实例。
4. 指标名、指标代码不得在页面模板中以字面量罗列；一律来自 `metrics` 接口或用户偏好。
5. 前端不做任何"补值/估算/插值"，缺失就是缺失。

## 四、动态指标机制

对应宪法原则三（指标可配置）。核心是 `utils/metrics.js` 的三个纯函数：

```text
GET /user/metric-preferences  →  preferences[]
GET /metrics/categories       →  分类 + 定义（含 categoryName / sortOrder）

watchlistColumns(preferences)
  └─ filter(enabled && showInWatchlist) → sort(sortOrder) → [metricCode]

buildCells(row.metrics, columns, limit)
  └─ [ { code, label, value, unit, pending } ]   ← 列表行的小格子

groupByCategory(values, categoryIndex)
  └─ [ { category, categoryName, items:[...] } ]  ← 详情页分组
```

页面模板只负责 `wx:for` 渲染，不含任何指标判断分支。用户在「指标展示配置」里增删、排序、改精度、换主数据源后，**前端代码零改动**。

## 五、状态管理

不使用状态库。跨页共享只有两处：

| 位置 | 内容 | 生命周期 |
|---|---|---|
| `app.globalData.preferenceDirty` | 指标偏好是否被修改过 | 进程内 |
| `wx.getStorageSync('lingkan_user_id')` | 本地用户标识 | 持久 |

其余数据由页面 `onLoad` / `onShow` 按需拉取，避免出现"缓存与后端不一致"的隐性 bug。

## 六、请求封装

`utils/request.js` 暴露 `http.get/post/put/del`：

- 拼接 `config.baseUrl + config.apiPrefix`
- 自动携带 `X-LingKan-User`（后端 `UserContext` 读取）
- 自动剔除 `undefined / null / ''` 参数
- `code === 0` 视为成功并返回 `data`，否则抛 `Error(message)`
- 默认统一 toast；需要自行处理的调用方传 `toastError: false`
- 超时 15s；对 `not in domain list` / `timeout` / `fail` 给出中文提示

## 七、图表

`components/line-chart` 使用 Canvas 2D（`type="2d"`）：

```text
properties: points[{date,value}] / stroke / chartHeight / unit
ready → createSelectorQuery().in(this).select('#lkChart').fields({node:true,size:true})
      → canvas.width = width * dpr；ctx.scale(dpr, dpr)
draw  → 参考线 + 渐变面积 + 折线 + 最新点 + 最高/最低/起止日期标注
```

不引入 ECharts：一是包体积，二是本项目只需要一条折线。

## 八、样式体系

- 设计令牌集中在 `app.wxss` 的 `page { --c-*: ... }`，组件内通过继承使用
- 组件样式隔离（默认 `isolated`），因此 `components/*/index.wxss` 各自独立定义，不依赖全局类
- 单位统一 `rpx`，设计基准 750
- 涨跌色遵循 A 股习惯：`--c-up` 红 / `--c-down` 绿 / `--c-flat` 灰
- 组件内部的类名统一加 `lk-` 前缀，避免与页面样式混淆

## 九、运行与构建

| 动作 | 说明 |
|---|---|
| 开发 | 微信开发者工具 → 导入项目 → 选择 `D:\project\lingkan\frontend` |
| AppID | 使用测试号或替换 `project.config.json` 中的 `appid` |
| 域名校验 | 开发阶段勾选「详情 - 本地设置 - 不校验合法域名」 |
| 发布 | 工具内「上传」→ 小程序后台提交审核 |

无构建产物，源码即产物。

## 十、已知限制

| 项 | 说明 |
|---|---|
| 全市场筛选条件 | 后端已支持 `metricFilters`，前端暂只做了排序与关键词/交易所筛选 |
| 自选拖拽排序 | 后端已支持 `PUT /watchlist/sort`，前端暂未实现拖拽交互 |
| 图表类型 | 仅折线图，暂无 K 线 / 柱状图 |
| 自动化测试 | 无（小程序端单测需额外引入 miniprogram-simulate） |
| 分享 | 仅详情页实现了 `onShareAppMessage` |
