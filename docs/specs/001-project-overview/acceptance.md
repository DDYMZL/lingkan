# Spec 001 — 验收标准

## A. Phase 0 验收（当前阶段）

| 编号 | 验收项 | 判定方法 | 结果 |
|---|---|---|---|
| A1 | 目录结构符合约束 | `tree /d/project/lingkan` 一级目录仅 frontend/backend/docker/docs | ✅ |
| A2 | 无 PC 管理后台 | 目录中不存在 admin/admin-web/web-admin | ✅ |
| A3 | 前端为微信小程序原生工程 | 存在 `app.json` / `app.js` / `app.wxss` / `project.config.json`，`app.json` 含 `pages` + `tabBar`，工程内无 `package.json` / `vite.config.*` / `*.vue` | ✅ |
| A4 | 前端可在开发者工具直接运行 | 导入 `frontend/` 后无编译配置缺失，10 个页面均可打开 | 待验证 |
| A5 | 后端可编译 | `mvn -DskipTests package` BUILD SUCCESS | 待验证 |
| A6 | 后端可启动 | `/actuator/health` 返回 `{"status":"UP"}` | 待验证 |
| A7 | 数据库可连接 | 迁移脚本执行，`schema_version` 表存在 3 条记录 | 待验证 |
| A8 | SDD 文档齐备 | constitution.md + 8 个 Spec 目录，每目录含 4 个文件 | ✅ |
| A9 | 数据源研究报告 | `docs/specs/004-data-source/source-research.md` 含 8 个数据源实测结论 | ✅ |

## B. Phase 1 验收

| 编号 | 验收项 | 判定方法 | 结果 |
|---|---|---|---|
| B1 | 每个候选数据源都有实测状态码 | 研究报告中逐源记录 HTTP 状态与样本 | ✅ |
| B2 | 明确区分"可用/部分可用/不可用" | 总览表标注 | ✅ |
| B3 | 落地方案写入迁移脚本 | V3 脚本中 enabled/verified 与实测一致 | ✅ |
| B4 | 不可用源不得编造接口 | 乐咕乐股标注 404；Tushare 标注需 Token | ✅ |

## C. 后续阶段验收要点（预留）

- 所有页面数值可回溯到 `data_providers` 中的一条记录。
- 指标配置修改后刷新页面立即生效（无需重新编译）。
- 任一数据源断开时，仅该源对应指标显示"取数失败"，其他指标不受影响。
- 关键计算单元测试全部通过（`mvn test`）。
