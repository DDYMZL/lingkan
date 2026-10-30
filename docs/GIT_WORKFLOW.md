# Git 与 GitHub 协作规范（GIT_WORKFLOW.md）

> 版本：v1.0　生效日期：2026-10-30
> 本文件是 `docs/constitution.md` 铁律 13「功能完成即提交」的执行细则，具有强制约束力。
> 约束对象：本项目全部参与者，**含 AI 编程工具**。

## 一、仓库信息

| 项 | 值 |
|---|---|
| 本地路径 | `D:\project\lingkan` |
| 远程仓库 | `https://github.com/DDYMZL/lingkan.git` |
| 远程别名 | `origin` |
| 主分支 | `main` |
| 身份 | `user.name = ziyou`，`user.email = 874928519@qq.com` |

## 二、核心规则：功能完成即提交

**每完成一个功能，必须立即 `commit` 并 `push` 到 GitHub。这是强制动作，不是可选项。**

### 1. 「一个功能」的判定

一次性满足以下全部条件，才构成一个可提交的功能单元：

1. 该功能对应的 Spec（`docs/specs/<模块>/spec.md`）已存在且状态为 `approved` 或以上；
2. 代码写完，且**可运行 / 可编译**（后端 `mvn -q -DskipTests package` 通过；前端 `node --check` 与微信开发者工具无报错）；
3. 该功能的测试已通过（关键计算必须有单元测试，见铁律 11）；
4. 文档已同步：对应 `design.md` / `tasks.md` 状态更新 + `docs/changes/changelog.md` 已登记。

**禁止**把多个不相关的功能攒成一次提交；**禁止**功能做完不提交、留在工作区等下次一起推。

### 2. 提交-推送的原子性

一次功能完成 = 一次 commit + 一次 push。commit 完成后**立即** push，不得只在本地堆积。

```bash
# 完成一个功能后的标准动作
git add -A
git commit -m "feat(watchlist): 自选股支持拖拽排序"
git push origin main
```

### 3. 中断与恢复

- 功能未做完但需切换任务时：`git stash push -u -m "wip: <功能名>"`，**不产生半成品 commit**；
- 若 push 失败（网络/鉴权），保留本地 commit，**下一次动作前必须先 push 补上**，不得继续叠加新功能。

## 三、提交信息规范

采用 Conventional Commits，`type(scope): 中文描述`。

| type | 含义 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `refactor` | 重构（不改外部行为） |
| `perf` | 性能优化 |
| `docs` | 仅文档变更 |
| `test` | 仅测试变更 |
| `build` / `ci` | 构建、依赖、流水线 |
| `chore` | 杂项（配置、脚本） |

- `scope` 取模块名：`stock` / `market` / `metrics` / `datasource` / `dividend` / `watchlist` / `history` / `scheduler` / `system` / `common` / `miniapp` / `docker` / `docs`。
- 描述用中文，动词开头，一句话说完，**不超过 50 字**，结尾不加句号。
- 一个 commit 只做一件事。正文（body）用于写**为什么改**，不写「改了什么」（diff 已经说明了）。

示例：

```
feat(metrics): 指标精度支持按用户偏好覆盖
fix(datasource): 腾讯财经 GBK 解码偶发乱码
docs(constitution): 补充 Git 提交纪律
```

**禁止**的写法：`update`、`修改`、`fix bug`、`提交`、`1`。

### 标签

功能完成后打 tag，格式 `v<主>.<次>.<修>`，与 `changelog.md` 的版本号一致：

```bash
git tag -a v0.3.0 -m "自选股排序功能上线"
git push origin v0.3.0
```

## 四、分支策略

个人项目，默认在 `main` 上直推。仅在以下情况开临时分支：

- 改动涉及 3 个以上模块，或预计跨越多次会话；
- 需要保留一份可回退的实验性实现。

分支命名：`feat/<功能名>`、`fix/<问题名>`，完成后合并回 `main` 并删除。

## 五、提交前自检清单

每次 `git add` 之前逐项确认：

- [ ] `git status` 中没有 `.tools/`、`.backup/`、`backend/target/`、`node_modules/`
- [ ] 没有提交任何密钥：数据库密码、数据源 Token、`docker/.env`、`*.pem` / `*.key`
- [ ] 没有提交 `*.log`、临时脚本、IDE 配置（`.idea/`、`.vscode/`）
- [ ] 数据库迁移脚本是**新增**而非修改历史脚本（铁律 5）
- [ ] `docs/changes/changelog.md` 已追加本次变更
- [ ] commit message 符合第三节格式

## 六、禁止入库清单

以下内容**永不入库**（已在 `.gitignore` 中声明，若发现被跟踪须立即 `git rm --cached` 移除）：

| 类别 | 示例 |
|---|---|
| 本地工具链 | `.tools/`（JDK、Maven，约 525 MB） |
| 备份目录 | `.backup/` |
| 构建产物 | `backend/target/`、`node_modules/`、`dist/`、`unpackage/` |
| 密钥与环境 | `.env`、`docker/.env`、`*.pem`、`*.key`、`*.jks` |
| 运行日志 | `*.log`、`logs/` |

> 生产配置必须走环境变量占位（`${LINGKAN_DB_PASSWORD}`），不得在 `application-*.yml` 中写明文口令。

## 七、回滚

- 单个功能回退：`git revert <commit>`（保留历史，优先用这个）；
- 禁止对已推送的 `main` 使用 `git reset --hard` + 强推，除非确认无其他协作者且已备份。

## 八、AI 编程工具附加要求

AI 在本项目内完成任何功能后，**必须自行执行**以下动作，不得等待用户提醒：

1. 按第二节自检并 `commit`；
2. `push` 到 `origin main`；
3. 在回复中报告 commit hash 与推送结果（成功 / 失败原因）。

若 push 因鉴权失败，必须明确告知用户「本地已提交，远程未推送」及待执行的命令，不得声称已完成。

## 九、命令速查

```bash
# 查看将要提交什么
git status --short

# 完成一个功能
git add -A && git commit -m "feat(scope): 描述" && git push origin main

# 查看最近提交
git log --oneline -10

# 版本标签
git tag -a v0.3.0 -m "说明" && git push origin v0.3.0

# 撤销某次已推送的提交
git revert <commit>
```
