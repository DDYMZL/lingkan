# 玲看股息 LingKan — 项目文档

> SDD（Specification-Driven Development）文档根目录。
> 所有功能开发前必须先有 Spec，代码必须与文档同步。

## 目录导航

| 路径 | 内容 |
|---|---|
| `constitution.md` | **项目宪法**，最高优先级约束，AI 与人均需先读 |
| `GIT_WORKFLOW.md` | **Git 协作规范**，功能完成即提交/推送的执行细则 |
| `specs/` | 各功能模块的 Spec（需求 / 设计 / 任务 / 验收） |
| `architecture/` | 系统、前端、后端、数据源、数据库架构 |
| `api/api-contract.md` | 前后端接口契约 |
| `database/schema.md` | 数据库表结构与索引说明 |
| `tasks/implementation-plan.md` | 分阶段实施计划与当前进度 |
| `decisions/ADR/` | 架构决策记录 |
| `changes/changelog.md` | 变更日志 |

## Spec 清单与状态

| 编号 | 模块 | 状态 |
|---|---|---|
| 001 | 项目总览 | approved |
| 002 | 股票市场 | approved（Phase 0 骨架已实现） |
| 003 | 指标中心 | approved（Phase 0 骨架已实现） |
| 004 | 数据源中心 | approved（Phase 1 研究完成） |
| 005 | 自选股 | approved（Phase 0 骨架已实现） |
| 006 | 股票详情 | approved（Phase 0 骨架已实现） |
| 007 | 历史数据 | draft（依赖指标快照落库） |
| 008 | 小程序 UI | approved（Phase 0 页面骨架已实现） |

状态机：`draft → review → approved → in_progress → implemented → verified`

## 阅读顺序建议

1. `constitution.md`（约束）
2. `GIT_WORKFLOW.md`（提交纪律）
3. `specs/001-project-overview/spec.md`（全景）
4. `architecture/system-architecture.md`（结构）
5. `api/api-contract.md`（接口）
6. 对应模块的 Spec

## 当前阶段

Phase 0（项目初始化）与 Phase 1（数据源研究）已完成，**等待用户确认后进入业务开发**。
详见 `tasks/implementation-plan.md`。
