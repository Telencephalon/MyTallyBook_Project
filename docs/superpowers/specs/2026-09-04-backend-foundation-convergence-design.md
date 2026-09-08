# 后端基础模块收敛设计

## 1. 文档状态

- 日期：2026-09-04
- 状态：方案 A 已由项目所有者确认
- 工作分支：`codex/mvp-development`
- 工作目录：`D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project-worktrees\codex-mvp-development`
- 实施边界：只修改隔离工作区；不部署、不推送、不修改服务器既有服务或端口。

## 2. 目标

在保留现有后端公共、安全、审计、认证和小程序纵向切片代码的前提下，消除数据库迁移、运行配置、端口、测试库生命周期和基础 HTTP 协议之间的不一致，形成后续业务模块可以稳定依赖的基线。

## 3. 已收敛决策

1. 产品只维护一个固定共享账本，有效成员最多 10 人，角色为 `OWNER`、`ADMIN`、`MEMBER`。
2. 普通成员只能修改、删除自己创建的账单；管理员和所有者可以处理全部账单。
3. 成员统计按 `book_entry.created_by` 聚合。
4. 邀请为限时、单次使用；初始化口令只能成功一次。
5. 会话原文只返回一次，数据库保存 `HMAC-SHA-256(APP_TOKEN_PEPPER, rawToken)` 的 64 位小写十六进制摘要。
6. 后端统一使用 Java 21、Spring Boot 4.1.0、MySQL 8.0.46、Maven Wrapper。
7. 本地和生产 Spring Boot 内部端口统一为 `127.0.0.1:7631`；生产只由 Nginx 反向代理访问该端口。
8. 敏感值只通过环境变量提供，不进入源码、Markdown、Git 或日志。
9. 认证、初始化、邀请等强事务场景使用 `JdbcTemplate` 和明确 SQL；后续普通 CRUD 可以使用 JPA。Hibernate 始终使用 `ddl-auto=validate`。

## 4. 迁移链修复

`account_book_dev` 已成功执行仓库原始 `V1__init_schema.sql`，因此 V1 从现在起不可变。隔离工作区对 V1 的修改必须恢复成原始内容，目标结构改由 `V2__align_approved_design.sql` 完成。

V2 包含：

- `ledger` 保留原 V1 已被外键引用的自增主键，并增加固定值唯一单例键；这以纯增量方式保证全库最多一条账本，初始化代码继续显式使用账本 ID `1`。
- `ledger_invite` 移除多次使用字段，增加 `used_by`、`used_at`、使用人外键和单次使用状态约束。
- `book_entry` 移除 `member_id` 外键和字段，增加 `(ledger_id, created_by, entry_date)` 索引。
- `audit_log.request_id` 从 `CHAR(36)` 扩展为 `VARCHAR(64)`。

V2 在任何破坏性列变更前检查邀请表中不存在无法无损转换的已使用邀请。检查失败时迁移必须停止，不能猜测 `used_by`。

禁止使用 `flyway repair` 修改已记录的 V1 校验和。新库按 V1、V2 顺序迁移；现有开发库只追加 V2。

## 5. 测试数据库安全模型

`account_book_test` 是固定的、可清理的自动化测试数据库，数据库本身和专用账号长期保留。测试不得连接或清理 `account_book_dev`、`account_book` 或其他数据库。

执行真实 MySQL 测试需要同时满足：

```text
DB_TEST_URL              精确选择 account_book_test
DB_TEST_USERNAME         account_book_test_app
DB_TEST_PASSWORD         仅当前进程提供
DB_TEST_RESET_ALLOWED    精确等于 account_book_test
```

测试启动时再次通过 `SELECT DATABASE()` 验证目标，只在验证通过后调用 Flyway 清理该 schema 内对象。测试结束后再次清理对象，但不执行 `DROP DATABASE`，也不需要 root 或管理员 JDBC 凭据。

迁移测试必须验证：

- 原始 V1 可以执行。
- 已处于 V1 的数据库可以升级到 V2。
- `flyway_schema_history` 同时存在成功的版本 1 和版本 2。
- 最终约束、字段和索引满足批准规格。
- 邀请 `USED` 状态必须同时具有 `used_by` 和 `used_at`。

## 6. 运行配置

通用 `application.yml` 保存非敏感默认值和环境变量占位符：

- `SERVER_ADDRESS` 默认 `127.0.0.1`。
- `SERVER_PORT` 默认 `7631`。
- DataSource 使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`。
- Hikari 最小连接数 1、最大连接数 5。
- Flyway 启用，Hibernate 只校验结构。
- 日志格式显示 MDC `requestId`。

`application-prod.yml` 只保留生产差异，不保存真实密码。`application-test.yml` 的默认单元测试继续排除数据库自动配置；真实 MySQL 测试使用显式环境变量独立启动。

小程序 `develop` 环境请求 `http://127.0.0.1:7631`。体验版和正式版仍只允许备案后的 HTTPS 域名。

## 7. 后端公共能力完成标准

保留并验证现有能力：

- `ApiResponse`、`ApiError` 和稳定错误码。
- Bean Validation 与全局异常处理。
- `X-Request-Id` 校验、MDC 和响应头回传。
- 无状态 Spring Security、Bearer Token、统一 401/403。
- 当前用户和三角色安全上下文。
- 事务内只追加审计及敏感字段递归脱敏。

补齐以下缺口：

- 参数类型错误和约束错误返回 400。
- 不支持的 HTTP 方法返回 405。
- 不支持的媒体类型返回 415。
- 数据完整性和乐观锁冲突返回 409。
- 匿名访问 `/actuator/health` 返回 200。
- 普通日志能够显示当前 `requestId`。

内部异常响应不得包含异常消息、SQL、令牌或密钥。服务端日志记录请求 ID、异常类型和不含消息的栈定位信息。

## 8. 验收

1. 默认 Maven 全量测试失败数和错误数均为 0；MySQL 门控测试的跳过数量必须如实报告。
2. 配置测试、Web/API 测试和安全测试全部通过。
3. 在显式测试数据库变量就绪时，V1 → V2 及认证 MySQL 集成测试全部通过。
4. `git diff --check` 通过。
5. 敏感信息扫描不发现真实 AppSecret、数据库密码、初始化口令、Token Pepper 或令牌。
6. 开发库启动时不出现 V1 校验和不一致，并成功记录 V2。
