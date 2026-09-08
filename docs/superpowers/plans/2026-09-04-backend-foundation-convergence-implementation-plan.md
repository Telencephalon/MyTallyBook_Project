# 后端基础模块收敛实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复现有隔离开发成果的迁移与配置冲突，并交付可供后续业务模块复用的后端公共、安全、审计和测试基线。

**Architecture:** 保留现有按业务能力分包的单体 Spring Boot 结构，认证与并发事务使用 `JdbcTemplate`，普通业务允许使用 JPA。数据库以不可变原始 V1 加增量 V2 演进，固定测试库通过专用账号和显式安全开关清理 schema 对象。

**Tech Stack:** Java 21、Spring Boot 4.1.0、Spring MVC、Spring Security、JdbcTemplate、Spring Data JPA、Flyway、MySQL 8.0.46、JUnit 5、MockMvc、Maven Wrapper。

**Spec:** [后端基础模块收敛设计](../specs/2026-09-04-backend-foundation-convergence-design.md)

## Global Constraints

- 只在 `codex/mvp-development` 隔离工作区修改。
- 不修改或覆盖主目录现有未提交内容。
- 不使用 Docker、Redis、微服务或消息队列。
- 不使用 `flyway repair`，不改写已经执行的 V1。
- 自动测试只能清理精确的 `account_book_test`，不得使用 root，不得删除数据库本身。
- Spring Boot 内部端口统一为 `127.0.0.1:7631`。
- 不在文件、命令输出、日志或提交中保存真实敏感值。
- 本轮不部署、不推送，完成后先报告验证结果。

---

### Task 1: 固化不可变 V1 并新增 V2

**Files:**
- Restore: `account-book-server/src/main/resources/db/migration/V1__init_schema.sql`
- Create: `account-book-server/src/main/resources/db/migration/V2__align_approved_design.sql`
- Modify: `account-book-server/src/test/java/com/mytallybook/accountbook/database/DatabaseMigrationTests.java`

**Interfaces:**
- Consumes: 已执行的原始 V1 数据库结构。
- Produces: 版本 2 的目标数据库结构和可验证的 Flyway 历史。

- [x] 在迁移集成测试中增加版本 `1,2` 历史、V1 → V2 升级和邀请状态 DML 约束断言。
- [x] 在真实 MySQL 门控环境运行测试，确认新增断言在没有 V2 时失败；若环境变量未提供，记录门控状态且不伪造 RED。
- [x] 将 V1 恢复为 `main` 当前原始内容。
- [x] 创建 V2，加入邀请数据前置保护及四组目标结构变更。
- [x] 在真实 MySQL 门控环境重新运行并确认通过。2026-09-05：Guard 定向 3/3；完整 Migration 6、Guard 3、Auth 1，共 10/10，失败/错误/跳过均为 0。

### Task 2: 统一固定测试库生命周期

**Files:**
- Modify: `account-book-server/src/test/java/com/mytallybook/accountbook/database/MysqlTestDatabaseSupport.java`
- Modify: `account-book-server/src/test/java/com/mytallybook/accountbook/database/MysqlTestDatabaseSupportTests.java`
- Modify: `account-book-server/src/test/java/com/mytallybook/accountbook/database/AuthMysqlIntegrationTests.java`
- Modify: `deploy/mysql/02-create-development-databases-and-users.sql`

**Interfaces:**
- Consumes: `DB_TEST_URL`、`DB_TEST_USERNAME`、`DB_TEST_PASSWORD`、`DB_TEST_RESET_ALLOWED`。
- Produces: `resetAndMigrate()`、受保护的连接和只清理 schema 对象的 `close()`。

- [x] 先修改测试，要求拒绝非 `account_book_test` URL、缺失确认值和错误确认值，并断言不再需要管理员 URL。
- [x] 运行 `MysqlTestDatabaseSupportTests`，确认旧实现按预期失败。
- [x] 实现精确目标验证、Flyway clean/migrate 和非删除数据库清理。
- [x] 运行测试并确认通过。
- [x] 更新数据库初始化脚本注释，明确测试库是可清理专用库。

### Task 3: 合并运行配置并统一 7631

**Files:**
- Modify: `account-book-server/src/main/resources/application.yml`
- Modify: `account-book-server/src/main/resources/application-prod.yml`
- Modify: `account-book-server/src/test/resources/application-test.yml`
- Modify: `account-book-server/src/test/java/com/mytallybook/accountbook/ProductionConfigurationTests.java`
- Modify: `account-book-miniapp/tests/env.test.ts`
- Modify: `account-book-miniapp/miniprogram/config/env.ts`

**Interfaces:**
- Produces: 默认 `127.0.0.1:7631`、环境变量 DataSource、Hikari 最大 5 和小程序开发地址。

- [x] 先把配置合同测试和小程序环境测试期望改为 7631/Hikari 5。
- [x] 运行定向测试并确认旧配置失败。
- [x] 合并主目录中已验证的非敏感 DataSource/Flyway 配置，消除 prod 重复配置。
- [x] 修改小程序开发地址为 `http://127.0.0.1:7631`。
- [x] 运行后端配置测试和小程序测试、类型检查。

### Task 4: 补齐基础 HTTP 错误与可观测性

**Files:**
- Modify: `account-book-server/src/main/java/com/mytallybook/accountbook/common/error/ErrorCode.java`
- Modify: `account-book-server/src/main/java/com/mytallybook/accountbook/common/error/GlobalExceptionHandler.java`
- Modify: `account-book-server/src/test/java/com/mytallybook/accountbook/common/ApiInfrastructureIntegrationTests.java`
- Modify if needed: `account-book-server/src/main/java/com/mytallybook/accountbook/security/BearerTokenAuthenticationFilter.java`

**Interfaces:**
- Produces: 400/405/415/409/500 稳定错误合同、匿名健康检查和带请求 ID 的日志上下文。

- [x] 先增加参数类型错误、方法不支持、媒体类型不支持、约束错误、数据冲突、乐观锁、匿名健康检查测试。
- [x] 运行定向测试并确认缺失映射产生预期失败。
- [x] 最小实现新的异常映射和错误码。
- [x] 增加 MDC 日志格式；500 日志只输出安全定位信息。
- [x] 运行公共协议、安全、认证 HTTP 回归测试。

### Task 5: 同步当前 Markdown 基线

**Files:**
- Modify: `README.md`
- Modify: `docs/03-微信共享记账小程序完整开发设计方案.md`
- Modify: `docs/07-微信共享记账小程序最终开发执行计划.md`
- Modify: `docs/08-后端公共协议安全与审计基础实施记录.md`
- Modify: `docs/09-微信登录会话与唯一所有者初始化实施记录.md`
- Modify: `docs/10-微信小程序首个纵向切片实施记录.md`

**Interfaces:**
- Documents: MySQL 8.0.46、统一 7631、原 V1 + V2、固定测试库、JDBC/JPA 边界和最新验证证据。

- [x] 更新当前有效文档，不把历史文档重新定义为执行入口。
- [x] 删除当前有效文档中“开发库尚不存在”“直接修改 V1”和本地 8080 等过期结论。
- [x] 所有命令只使用变量名或占位符，不写真实密钥。

### Task 6: 全量验证与交付检查点

**Files:**
- Verify only: entire isolated worktree.

**Interfaces:**
- Produces: 可复现的测试、格式、敏感信息和迁移状态报告。

- [x] 运行 `account-book-server\\mvnw.cmd clean verify`。
- [x] 运行 `npm test` 和 `npm run typecheck`。
- [x] 运行 Nginx 模板测试与 `git diff --check`。
- [x] 执行只输出命中数量和文件路径的敏感信息扫描。
- [x] 如测试数据库变量可用，运行 V1 → V2 和认证 MySQL 集成测试；否则明确报告跳过，提供用户可直接执行的安全命令。
- [x] 不提交、不推送；先向项目所有者报告变更和验证结果。2026-09-05 结果已写入 `docs/11-后端基础模块收敛实施记录.md`；开发库 V2 和生产部署尚未执行。
