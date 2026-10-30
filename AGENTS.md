# AGENTS.md — AI 工具执行约定

本文件对在本仓库内工作的所有 AI 编程工具具有强制约束力。开工前先读 `docs/constitution.md` 与 `docs/GIT_WORKFLOW.md`。

## 必须遵守的三个动作

1. **开工前**：读 `docs/constitution.md`，再读本次功能对应的 `docs/specs/<模块>/`。Spec 未经确认不得编码。
2. **完成一个功能后**：运行测试 → 更新 Spec 与 `docs/changes/changelog.md` → **立即 `git commit` 并 `git push origin main`**。
3. **回复用户时**：报告 commit hash 与推送结果。push 失败必须明说「本地已提交、远程未推送」。

## 提交格式

```
type(scope): 中文描述
```

`type` ∈ `feat` / `fix` / `refactor` / `perf` / `docs` / `test` / `build` / `ci` / `chore`。
`scope` 取模块名（`stock` / `metrics` / `datasource` / `watchlist` / `miniapp` / `docker` / `docs` 等）。

## 红线

- 不提交 `.tools/`、`.backup/`、`backend/target/`、`node_modules/`、`.env`、密钥、日志。
- 不修改已提交的数据库迁移脚本，只新增。
- 不对已推送的 `main` 强推。
- 不编造数据源接口与指标公式；取不到数就返回 `MISSING`。

详见 `docs/GIT_WORKFLOW.md`。
