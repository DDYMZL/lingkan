# 008-小程序 UI · 技术设计

> 版本：v1.1（前端改为微信小程序原生开发）
> 日期：2026-10-30

## 一、工程配置

### 1.1 `project.config.json`

```json
{
  "appid": "touristappid",
  "compileType": "miniprogram",
  "miniprogramRoot": "./",
  "libVersion": "3.5.5",
  "setting": { "urlCheck": false, "es6": true, "minifyWXSS": true, "minifyWXML": true }
}
```

> 开发阶段 `urlCheck: false` 允许请求开发机地址；dev 的 `baseUrl` 使用开发机局域网 IP（`http://192.168.10.115:8080`），模拟器与真机调试共用——`localhost` 在真机上指向手机自身，不可用。
> 上线前必须改为已备案 HTTPS 域名，并在小程序后台配置 request 合法域名。
> `appid` 上线前替换为真实 AppID。

### 1.2 `app.json`

- `pages`：10 个页面，首页置首
- `tabBar` 四入口（自选 / 全市场 / 配置 / 我的），图标位于 `assets/tabbar/`
- `window`：白色导航栏 + `#F4F5F7` 背景
- `lazyCodeLoading: requiredComponents`，减小启动包体
- 页面级 `enablePullDownRefresh` 仅首页、全市场、详情、配置页、数据源页开启

### 1.3 环境配置

`config/index.js` 集中管理 `baseUrl` / `apiPrefix` / `timeout`，切换 `ACTIVE_ENV` 即切环境，避免地址散落在代码里。

## 二、目录与职责

```text
frontend/
├── pages/            10 个页面，只做「编排 + 布局」
├── components/       纯展示组件，不含请求（state-tag / empty-state / line-chart）
├── services/         唯一发起 HTTP 的地方（6 个）
├── utils/            请求封装、格式化、指标组装、本地用户标识（纯函数）
├── config/           环境配置
└── assets/tabbar/    TabBar 图标
```

**硬约束**：`pages/` 内不得出现 `wx.request`，不得出现指标代码字符串字面量（除判定基础信息类的白名单外）。

## 三、动态指标渲染（核心机制）

### 3.1 数据流

```text
后端 GET /stocks 返回 metrics: { [metricCode]: MetricValueDto }
                    │
                    ▼
GET /user/metric-preferences（启用、顺序、精度、单位、显示名、主数据源）
                    │
                    ▼
utils/metrics.js :: watchlistColumns(preferences)
      filter(enabled && showInWatchlist && !isBasic) → sort(sortOrder)
                    │
                    ▼
utils/metrics.js :: buildCells(row.metrics, columns, 3)
      → [{ code, label, value, unit, pending }]
                    │
                    ▼
WXML  wx:for 渲染，页面内无任何指标判断分支
```

详情页使用 `groupByCategory(values, categoryIndex)`，按 `/metrics/categories` 给出的分类与顺序分组。

### 3.2 指标渲染决策表

| 后端 `status` | 渲染 |
|---|---|
| `OK` | `displayValue` + `unit` |
| `MISSING` | 「--」+ 缺失原因（`statusText`） |
| `STALE` | 数值 + 橙色「过期」标签 |
| `ERROR` | 「--」+ 红色「异常」标签 |
| `PENDING` | 只显示「待确认」+ `statusText`（**不展示任何计算结果**） |
| `NOT_APPLICABLE` | 「--」+ 灰色「不适用」 |

补充规则：

- 涨跌类数值：正 → 红（`--c-up`），负 → 绿（`--c-down`），0 → 灰
- 每个指标都会展示「来源 + 数据时间」，点击进入多数据源对比
- 后端 `available === false` 时（市场概览 / 行情），只展示空态文案，绝不估算

**这是全项目唯一处理指标展示的规则集**，落在 `utils/metrics.js` + `components/state-tag`，新增指标不需要改页面。

## 四、状态管理

不使用状态库。跨页共享只有：

| 位置 | 内容 | 说明 |
|---|---|---|
| `app.globalData.preferenceDirty` | 偏好是否被修改 | 配置页保存后置 true，首页/列表 `onShow` 时据此重拉 |
| `wx.storage['lingkan_user_id']` | 本地用户标识 | 通过 `X-LingKan-User` 传给后端 |

其余数据由页面按需拉取，宁可多一次请求，也不留"缓存与后端不一致"的隐性 bug。

## 五、请求层

`utils/request.js`：

- 统一 baseURL（来自 `config/index.js`）
- 统一注入 `X-LingKan-User`
- 自动剔空参数（`undefined` / `null` / `''`）
- 统一解包 `{ code, message, data }`，`code === 0` 才算成功
- `code !== 0` 抛 `Error(message)`；网络失败给出中文可诊断提示
- 默认统一 toast，需要自行处理的调用传 `toastError: false`
- 超时 15s

## 六、图表

`components/line-chart`（Canvas 2D，`type="2d"`）：

```text
ready  → createSelectorQuery().in(this).select('#lkChart').fields({ node, size })
       → canvas.width = width * dpr；ctx.scale(dpr, dpr)
draw   → 参考线 + 渐变面积 + 折线 + 最新点高亮 + 最高/最低/起止日期标注
```

不引入 ECharts：只需一条折线，自研约 150 行即可，避免主包超限风险。

## 七、主题

`app.wxss` 的 `page` 选择器内定义 CSS 变量（组件可通过继承使用）：

```css
--c-primary: #b3272d;   /* 主色：中国红 */
--c-up: #d93a3a;        /* 上涨 红 */
--c-down: #0f9757;      /* 下跌 绿 */
--c-flat: #6b7075;      /* 平盘 灰 */
--c-bg: #f4f5f7;
--c-card: #ffffff;
```

组件样式默认隔离，`components/*/index.wxss` 各自定义样式并统一使用 `lk-` 前缀。

## 八、运行与预览

无需构建：

```text
微信开发者工具 → 导入项目 → 目录选择 D:\project\lingkan\frontend
→ 详情 - 本地设置 - 勾选「不校验合法域名」（开发阶段）
```

## 九、风险

| 风险 | 说明 |
|---|---|
| 真机域名 | dev 走开发机局域网 IP（`192.168.10.115:8080`），需手机同局域网且防火墙放行 8080；IP 为 DHCP 分配，变动需同步改配置；对外发布仍需 HTTPS 域名 |
| 未做 UI 自动化 | 页面渲染正确性依赖人工走查 |
| 图表精度 | Canvas 2D 需基础库 ≥ 2.9.0，已通过 `libVersion: 3.5.5` 保证 |
