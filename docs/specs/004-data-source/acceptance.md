# 004-数据源中心 · 验收标准

> 版本：v1.0
> 日期：2026-10-30

## 一、功能验收

| 编号 | 验收项 | 判定方式 | 当前结果 |
|---|---|---|---|
| A4-01 | `GET /api/v1/data-sources` 返回全部内置数据源 | 接口返回条数 = 8 | ⏳ 待联调（需数据库） |
| A4-02 | 每个数据源返回 `supportedMetrics` 列表 | 响应字段非空 | ⏳ |
| A4-03 | 响应中**不含** Token 明文字段 | 检查 DTO 字段 | ✅ 代码已保证（`ProviderDto` 无 token 字段） |
| A4-04 | `POST /data-sources/tencent/test` 返回 `success=true` | 真实请求上游 | ⏳ 待联调 |
| A4-05 | `POST /data-sources/legulegu/test` 返回 `success=false` 且说明未实现 | 真实调用 | ⏳ 待联调 |
| A4-06 | 停用数据源后，`test` 返回「已停用」提示 | 先 disable 再 test | ⏳ |
| A4-07 | 测试结果写回 `health_status` / `last_success_at` | 查库 | ⏳ |
| A4-08 | `GET /stocks/{code}/metrics/{metric}/sources` 返回多源数组 | 响应结构符合设计 | ⏳ |
| A4-09 | 多源对比结果**不含**平均值字段 | 响应结构检查 | ✅ 设计禁止聚合 |
| A4-10 | `GET /data-sources/{code}/logs` 返回最近日志，limit 上限生效 | 传 limit=1000 观察被截断 | ⏳ |

## 二、数据正确性验收（关键）

| 编号 | 验收项 | 期望 |
|---|---|---|
| A4-11 | 腾讯、东方财富、新浪三源返回的 600519 最新价一致 | 差异 ≤ 0.01 元 |
| A4-12 | 腾讯返回的中文股票名不乱码 | GBK 正确解码 |
| A4-13 | 单位换算正确：腾讯成交额（万元）→ 元；东方财富价格 ÷100 | 与页面展示数量级一致 |
| A4-14 | `dataTime` 与 `fetchTime` 不相等且 `fetchTime ≥ dataTime` | 抽样检查 |
| A4-15 | 停牌或无此标的时，状态为 `MISSING` 而非 `SUCCESS` 且值为 0 | 用不存在代码 `sh999999` 验证 |

## 三、约束验收

| 编号 | 验收项 | 判定 |
|---|---|---|
| A4-16 | Controller 与 Service 中不存在第三方域名常量 | 代码检索 `eastmoney` / `gtimg` / `sinajs` 仅出现在 `provider/impl` | ✅ 已检查 |
| A4-17 | 新增数据源不需要修改业务代码 | 新增一个 `@Component` 即可被 `ProviderRegistry` 发现 | ✅ 机制已实现 |
| A4-18 | 所有外部请求均可配置超时 | `lingkan.http.*` 生效 | ✅ |
| A4-19 | 采集任务默认关闭 | `lingkan.sync.enabled=false` | ✅ |

## 四、未达成项（必须在 Phase 3 前解决）

1. **A4-01 ~ A4-10 全部依赖数据库连通**：当前 openGauss 容器未启动，接口无法真实运行。这是最大阻塞项。
2. **A4-11 三源一致性**：仅在 Phase 1 用 PowerShell 直连验证过，Java 适配器落地后的结果一致性未验证。
3. **T4-30 限流**：当前适配器为简单 sleep，未做并发安全令牌桶。

## 五、验收签署

| 角色 | 结论 | 日期 |
|---|---|---|
| 需求方（用户） | 待确认 | — |
| 开发（AI） | Phase 1 研究部分已交付；运行时验收阻塞于 Docker | 2026-10-30 |
