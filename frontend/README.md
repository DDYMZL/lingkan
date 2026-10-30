# 玲看股息 · 微信小程序（原生）

原生微信小程序前端，无 npm 依赖、无构建步骤，**源码即产物**。

## 一、运行

1. 打开微信开发者工具 → 导入项目
2. 目录选择 `D:\project\lingkan\frontend`
3. AppID：使用「测试号」，或把 `project.config.json` 里的 `touristappid` 换成你自己的 AppID
4. 详情 → 本地设置 → 勾选 **不校验合法域名**（开发阶段必须，否则请求 `http://localhost:8080` 会被拦截）
5. 确认后端已启动（默认 `http://localhost:8080`）

## 二、接口地址

统一在 `config/index.js` 配置：

```js
const ENVS = {
  dev:  { baseUrl: 'http://localhost:8080', debug: true },
  prod: { baseUrl: 'https://api.lingkan.cn', debug: false }   // 上线前替换
};
const ACTIVE_ENV = 'dev';
```

请求会拼成 `{baseUrl}/api/v1{path}`，并自动带上 `X-LingKan-User` 头（本地生成的稳定用户标识，不做登录）。

## 三、目录

```text
frontend/
├── app.js / app.json / app.wxss / sitemap.json / project.config.json
├── config/index.js        环境配置
├── utils/
│   ├── request.js         请求封装（Promise + 统一错误）
│   ├── format.js          时间/数值/涨跌色/状态标签
│   ├── metrics.js         动态指标组装（核心）
│   └── user.js            本地 user_id
├── services/              6 个后端接口映射
├── components/            state-tag / empty-state / line-chart
├── pages/                 10 个页面
└── assets/tabbar/         8 个 tabBar 图标
```

## 四、页面

| 页面 | 路径 | 说明 |
|---|---|---|
| 自选（tab） | `pages/home/index` | 市场概览 + 自选股 + 交易时段 |
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
2. **不编口径**：`calculationStatus === 'PENDING'` 的指标（周30 / 周中 / 周60 / 周下 / 周上 / 带内位置 / 近 3 天创 60 日新高低）只显示「待确认」，不输出任何计算结果。
3. **可追溯**：每个指标都展示来源与数据时间，点击进入多源对比，不做平均。

## 六、动态指标

页面里**没有任何写死的指标名**。列与分组全部来自：

- `GET /api/v1/user/metric-preferences` → 启停、顺序、精度、展示位置、主数据源
- `GET /api/v1/metrics/categories` → 分类与定义

用户在「指标配置」页做的改动，前端代码零改动即可生效。

## 七、已知限制

- 自选拖拽排序未实现（后端 `PUT /watchlist/sort` 已就绪）
- 全市场条件筛选 UI 未做（后端 `metricFilters` 已支持）
- 图表仅折线图，无 K 线
- 真机调试需要 HTTPS 域名，`localhost` 仅开发者工具可用
