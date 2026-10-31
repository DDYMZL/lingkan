# 玲看股息 · 微信小程序（原生）

原生微信小程序前端，无 npm 依赖、无构建步骤，**源码即产物**。

## 一、运行

1. 打开微信开发者工具 → 导入项目
2. 目录选择 `D:\project\lingkan\frontend`
3. AppID：使用「测试号」，或把 `project.config.json` 里的 `touristappid` 换成你自己的 AppID
4. 详情 → 本地设置 → 勾选 **不校验合法域名**（开发阶段必须，否则请求 `http://192.168.10.115:8080` 会被拦截）
5. 确认后端已启动（默认监听 `0.0.0.0:8080`；dev 地址为开发机局域网 IP，见下）

## 二、接口地址

统一在 `config/index.js` 配置：

```js
const ENVS = {
  dev:  { baseUrl: 'http://192.168.10.115:8080', debug: true },  // 开发机局域网 IP，模拟器/真机共用
  prod: { baseUrl: 'https://api.lingkan.cn', debug: false }   // 上线前替换
};
const ACTIVE_ENV = 'dev';
```

请求会拼成 `{baseUrl}/api/v1{path}`，并自动带上 `X-LingKan-User` 头（本地生成的稳定用户标识，不做登录）。

真机调试三要素：手机与开发机同一局域网；`dev.baseUrl` 为开发机局域网 IP（`ipconfig` 核对，DHCP 变动需同步改）；Windows 防火墙放行 8080 入站
（`New-NetFirewallRule -DisplayName "LingKan backend 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow`）。

## 三、目录

```text
frontend/
├── app.js / app.json / app.wxss / sitemap.json / project.config.json
├── config/index.js        环境配置
├── utils/
│   ├── request.js         请求封装（Promise + 统一错误）
│   ├── format.js          时间/数值/涨跌色/状态标签
│   ├── metrics.js         动态指标组装（核心）
│   ├── nav.js             页面跳转（个股详情带名称，供详情页同步设标题）
│   └── user.js            本地 user_id
├── services/              6 个后端接口映射
├── components/            state-tag / empty-state / line-chart
├── pages/                 10 个页面
└── assets/tabbar/         8 个 tabBar 图标
```

## 四、页面

| 页面 | 路径 | 说明 |
|---|---|---|
| 自选（tab） | `pages/home/index` | 十列横滚看板（首列固定 + 表头吸顶）+ 行内展开六档目标价 + 行情 WS 增量 |
| 全市场（tab） | `pages/ranking/index` | 分页列表 + 关键词/交易所筛选 + 按指标排序 |
| 配置（tab） | `pages/config/index` | 配置入口 + 数据源健康状态 |
| 我的（tab） | `pages/about/index` | 版本、数据来源、使用边界、免责 |
| 搜索 | `pages/search/index` | 搜索并加入自选 |
| 个股详情 | `pages/stock/index` | 行情 + 分类指标（含来源/时间）+ 历史折线 + 分红 |
| 多源对比 | `pages/compare/index` | 单指标各数据源并列 + 切换主数据源 |
| 指标配置 | `pages/config-metrics/index` | 启停 / 展示位置 / 精度 / 排序 / 主数据源 |
| 数据源管理 | `pages/datasource/index` | 连通性测试 / 启停 |
| 采集日志 | `pages/datasource-logs/index` | 最近 30 条采集记录 |

## 五、三条不可违背的展示原则

1. **不估算**：后端 `available === false` 时只显示空态，不补值、不推测。
2. **不编口径**：`calculationStatus === 'PENDING'` 的指标（周30 / 带内位置 / 近 3 天创 60 日新高低）只显示「待确认」，不输出任何计算结果。周60/周120/周上/周中/周下已于 2026-10-31 经用户确认口径（V6 迁移转 READY），首页正常展示数值。
3. **可追溯**：每个指标都展示来源与数据时间，点击进入多源对比，不做平均。

## 六、动态指标

页面里**没有任何写死的指标名**。列与分组全部来自：

- `GET /api/v1/user/metric-preferences` → 启停、顺序、精度、展示位置、主数据源
- `GET /api/v1/metrics/categories` → 分类与定义

用户在「指标配置」页做的改动，前端代码零改动即可生效。

## 七、首页看板布局与导航栏约定

- 首页表格是**单个双向 `scroll-view`（scroll-x + scroll-y + 固定高度）**：`overflow-x` 容器自身就是 `position: sticky` 的滚动端口，表头吸顶只能相对它生效，所以纵向滚动不能交给页面，否则十列表格滑下去就看不见列名。表头 `sticky top`、首列与展开区 `sticky left`，首列背景不透明以遮住滑过的列。
- 表宽写死 `1314rpx`（首列 220 + 四列 118/128/150/118 + 周线五列 116×5）：WXSS 里 rpx 放进 CSS 变量或 `calc()` 换算不可靠，列宽合计直接写死并在 `pages/home/index.wxss` 留档。
- 列定义只有一份 `COLUMNS` 数组（`pages/home/index.js`），表头与表体同源渲染；WebSocket 增量只 patch `rows[i].cells`，不整页重排。
- 跳转个股详情统一走 `utils/nav.goStockDetail(code, name)`：名称随路由下发，详情页 `onLoad` 同步设置导航栏标题；详情页异步回调写标题前检查 `onUnload` 置的 `detached` 标记，防止用户秒退时把个股名写到栈顶其它页面（实测会污染首页标题）。

## 八、已知限制

- 自选拖拽排序未实现（后端 `PUT /watchlist/sort` 已就绪）
- 全市场条件筛选 UI 未做（后端 `metricFilters` 已支持）
- 图表仅折线图，无 K 线
- 真机调试走开发机局域网 IP（需同局域网 + 防火墙放行 8080）；对外发布仍需 HTTPS 域名
