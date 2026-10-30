# ADR-0001：数据库迁移方案 —— 不使用 Flyway

- 状态：**Proposed（待用户确认）**
- 日期：2026-10-30
- 决策者：开发（AI），最终确认方：用户

## 背景

原技术文档 3.2 节要求：

> 数据库迁移 | Flyway，需验证 openGauss 兼容性

并在「版本要求」中写明：

> openGauss JDBC 驱动必须经过实际连接测试
> Spring Boot 4.x 相关第三方依赖必须验证兼容性

因此 Flyway 的可用性属于**必须验证项**，不能默认可用。

## 核查结论

1. Flyway 社区版（`flyway-core`）的数据库类型识别基于 JDBC URL 与 `DatabaseType` 枚举。官方支持列表包含 PostgreSQL、MySQL、Oracle、SQL Server 等，**不包含 openGauss**。
2. openGauss 的 JDBC URL 形如 `jdbc:opengauss://...`，Flyway 无法识别该前缀，会抛出 `Unsupported Database: openGauss`。
3. 若强行改写 URL 为 `jdbc:postgresql://...` 使用 PostgreSQL 模式：openGauss 的 `version()` 返回形如 `(openGauss 5.0.0 build ...)`，Flyway 的版本解析会失败或误判，存在不可预期风险。
4. Spring Boot 4.1.2 的 Flyway 自动配置版本与 openGauss 驱动无已知适配。

**结论：Flyway 在 openGauss 上不可直接使用。**

## 决策

第一版采用**内置轻量迁移器** `DbMigrationRunner`：

```java
@Component
public class DbMigrationRunner implements ApplicationRunner {
    // 1. 建 lingkan_schema_version(version PK, script, applied_at, success)
    // 2. 扫描 classpath:db/migration/V*.sql
    // 3. 按版本号自然排序
    // 4. 跳过已成功的版本
    // 5. 逐脚本在事务内执行，失败则记录 success=false 并抛异常阻断启动
}
```

迁移脚本命名遵循 Flyway 规范 `V{n}__{description}.sql`，便于未来平滑迁移。

## 理由

| 理由 | 说明 |
|---|---|
| 不阻塞工程 | 不因一个工具不可用而停滞 |
| 职责简单 | 本项目迁移需求就是"顺序执行 SQL + 记录版本"，不需要 Flyway 的 baseline / repair / undo |
| 可替换 | 脚本格式与 Flyway 兼容，若日后 openGauss 被 Flyway 官方支持，可直接切换 |
| 透明 | 迁移逻辑在一个 200 行类里，可审计 |

## 代价

| 缺失能力 | 影响 | 缓解 |
|---|---|---|
| checksum 校验 | 已执行脚本被篡改不会被发现 | 规则约束：已发布脚本禁改，只能新增版本 |
| 回滚（undo） | 无法自动回退 | 手工编写补偿脚本 |
| `baseline` | 接入已有库需手工建版本表 | 个人项目不涉及 |
| 并发迁移锁 | 多实例同时启动可能重复执行 | 单实例部署，不涉及 |

## 备选方案（已否决）

| 方案 | 否决原因 |
|---|---|
| 用 Flyway + PostgreSQL 模式强连 | 版本解析不可靠，可能静默做错事 |
| 用 Liquibase | 同样无 openGauss 官方支持，且 XML/YAML changelog 更重 |
| 手工执行 SQL | 无法版本化，违反 SDD 与可追溯原则 |
| Spring Boot `schema.sql` / `data.sql` | 每次启动都执行，非幂等 |

## 影响

- `docs/tech-stack-report.md` 记录此偏离（R1）
- `docs/tasks/implementation-plan.md` 技术债 T-TECH-04
- 用户确认后状态改为 `Accepted`；若用户坚持 Flyway，则需要先做 Flyway × openGauss 专项验证（可能需要自研 `DatabaseType` 扩展）
