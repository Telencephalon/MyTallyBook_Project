# Task 4 微信登录、会话与唯一所有者初始化设计

## 1. 文档状态

- 日期：2026-08-30
- 范围：后端微信登录、HMAC 会话、一次性初始化、当前用户、固定账本查询和退出
- 前置任务：Task 0～Task 3 已完成
- 工作分支：`codex/mvp-development`
- 部署边界：本任务只在隔离 worktree 开发和测试，不部署、不修改生产 `account_book`，不改动京东云既有服务或端口

## 2. 已确认决策

1. 初始化采用方案 B：首次登录只返回 `NEED_BOOTSTRAP`；初始化时重新调用 `wx.login()`，提交新的微信 code 和初始化口令。
2. 会话摘要使用 `HMAC-SHA256(rawToken, APP_TOKEN_PEPPER)`，数据库保存 64 位小写十六进制摘要。
3. 每个微信用户只保留一个有效会话；重新登录时撤销该用户所有旧会话。
4. `AUTHENTICATED`、`NEED_BOOTSTRAP`、`INVITE_REQUIRED` 使用 HTTP 200；停用用户使用 HTTP 403 `USER_DISABLED`。
5. 并发初始化在京东云独立临时 `account_book_test` 上验证；若同名数据库已存在则停止，不覆盖。
6. 数据访问采用 `JdbcTemplate + 明确 SQL + Spring 事务`，不为全部表提前创建 JPA 实体，不使用存储过程。
7. 固定账本默认名称为“共享账本”。

## 3. 范围

### 3.1 本任务实现

- 微信 `code2Session` 服务端客户端。
- 普通登录状态判断。
- 一次性初始化口令和唯一 OWNER 初始化。
- 32 字节随机会话 Token、HMAC 摘要、30 天有效期和单会话轮换。
- 数据库会话校验器。
- 当前用户查询和资料更新。
- 固定账本查询。
- 当前会话退出。
- 登录、初始化、退出和资料更新的必要审计。
- 本地 Stub 微信测试和真实 MySQL 隔离集成测试。

### 3.2 本任务不实现

- 邀请生成、邀请接受和成员管理；这些属于 Task 6。
- 角色调整、所有权转让和成员人数上限并发控制。
- 小程序页面和请求层；这些属于 Task 5。
- 真实微信接口联调。
- 生产数据库迁移和服务器部署。

## 4. 组件设计

### 4.1 微信接口边界

`WechatSessionClient` 是业务层依赖的接口：

```java
WechatIdentity exchange(String code);
```

`WechatIdentity` 只包含：

```text
openid
unionid（可空）
```

`WechatCode2SessionClient` 使用 Spring `RestClient` 调用：

```text
GET https://api.weixin.qq.com/sns/jscode2session
    ?appid=...
    &secret=...
    &js_code=...
    &grant_type=authorization_code
```

微信响应中的 `session_key` 只用于确认返回格式，解析后立即丢弃，不进入业务 DTO、日志、异常、审计或响应。

连接超时为 3 秒，读取超时为 5 秒。后端不自动重试 `code2Session`，因为微信 code 是一次性短期凭证；需要重试时由小程序重新执行 `wx.login()` 获取新 code。

### 4.2 认证业务组件

```text
AuthController
  ├─ AuthService
  │   ├─ WechatSessionClient
  │   ├─ AuthStore
  │   └─ SessionTokenService
  └─ BootstrapService
      ├─ WechatSessionClient
      ├─ AuthStore
      ├─ SessionTokenService
      └─ AuditLogService

UserController
  └─ UserService
      ├─ AuthStore
      └─ AuditLogService

LedgerController
  └─ LedgerQueryService
      └─ AuthStore

BearerTokenAuthenticationFilter
  └─ DatabaseSessionTokenVerifier
      ├─ AuthStore
      └─ SessionTokenService
```

`AuthStore` 定义业务所需的数据访问语义；`JdbcAuthStore` 集中实现 SQL。业务服务不拼接 SQL，控制器不直接访问数据库。

### 4.3 可测试性

- `Clock` 作为 Bean 注入，测试可固定时间。
- `WechatSessionClient` 可替换为 Stub，测试不访问真实微信。
- `SessionTokenService` 注入 `SecureRandom`，测试可使用确定性随机源。
- `AuthStore` 接口允许业务状态测试使用内存实现；SQL、事务和行锁行为仍必须由真实 MySQL 测试覆盖。

## 5. 配置设计

生产环境只通过环境变量提供：

```text
WECHAT_APP_ID
WECHAT_APP_SECRET
APP_BOOTSTRAP_KEY
APP_TOKEN_PEPPER
```

内部配置前缀：

```yaml
app:
  wechat:
    app-id: ${WECHAT_APP_ID}
    app-secret: ${WECHAT_APP_SECRET}
    connect-timeout: 3s
    read-timeout: 5s
  auth:
    bootstrap-key: ${APP_BOOTSTRAP_KEY:}
    token-pepper: ${APP_TOKEN_PEPPER}
    session-ttl: 30d
    token-bytes: 32
```

规则：

- AppID、AppSecret 和 Token pepper 必须非空。
- Token pepper 至少 32 个字符，不得提交 Git，不得写日志。
- 初始化口令配置后必须为 20～256 个字符；接口收到的口令也必须为 20～256 个字符。
- 初始化成功后允许从环境文件删除 `APP_BOOTSTRAP_KEY` 并重启服务。
- 尚未初始化且未配置初始化口令时，初始化接口返回 `BOOTSTRAP_NOT_CONFIGURED`。
- 更换 Token pepper 会使全部旧 Token 失效；除非执行主动全员下线，否则不得更换。
- 测试 profile 只使用明显的测试占位值，不使用生产密钥。

## 6. HTTP 接口合同

所有响应沿用 Task 3 的 `ApiResponse` 和 `ApiError`，并返回最终 `requestId`。下文 JSON 为统一响应中的 `data` 内容，控制器不得绕过统一响应外层。

### 6.1 微信登录

```http
POST /api/v1/auth/wechat/login
Content-Type: application/json

{
  "code": "一次性微信code"
}
```

`code` 去除首尾空白后必须为 1～256 个字符。成功状态：

```json
{
  "state": "NEED_BOOTSTRAP"
}
```

```json
{
  "state": "INVITE_REQUIRED"
}
```

```json
{
  "state": "AUTHENTICATED",
  "token": "仅返回一次的原始Token",
  "expiresAt": "2026-09-29T18:00:00+08:00"
}
```

未初始化或未加入账本时，不创建 `app_user`，不创建 `auth_session`。

### 6.2 初始化所有者

```http
POST /api/v1/auth/bootstrap
Content-Type: application/json

{
  "code": "新的微信code",
  "bootstrapKey": "部署时配置的一次性口令"
}
```

成功返回 `AUTHENTICATED`、原始 Token 和到期时间。初始化口令必须为 20～256 个字符，不符合限制时按参数错误处理；校验失败响应不得泄露是长度错误还是内容错误之外的配置信息。

### 6.3 退出

```http
POST /api/v1/auth/logout
Authorization: Bearer <token>
```

成功返回：

```json
{
  "state": "LOGGED_OUT"
}
```

退出只撤销当前 Token；重新登录、停用用户和移除成员会撤销该用户全部有效 Token。

### 6.4 当前用户

```http
GET /api/v1/users/me
Authorization: Bearer <token>
```

返回：

```json
{
  "userId": 1,
  "nickname": "微信用户",
  "avatarUrl": null,
  "ledgerId": 1,
  "memberId": 1,
  "role": "OWNER",
  "displayName": null
}
```

资料更新：

```http
PATCH /api/v1/users/me
Authorization: Bearer <token>
Content-Type: application/json

{
  "nickname": "家庭成员",
  "avatarUrl": "https://example.invalid/avatar.png"
}
```

至少提供一个字段。昵称去除首尾空白后必须为 1～64 个字符；`avatarUrl` 最长 512 个字符，只接受 HTTPS 地址，传入 `null` 或空字符串表示清除头像。请求 DTO 必须保留字段是否出现的状态：未出现表示不修改，出现且为 `null` 才表示主动清除；昵称出现且为 `null` 属于参数错误。

### 6.5 固定账本

```http
GET /api/v1/ledger
Authorization: Bearer <token>
```

返回：

```json
{
  "id": 1,
  "name": "共享账本",
  "currency": "CNY",
  "timezone": "Asia/Shanghai",
  "maxMembers": 10
}
```

## 7. 登录状态判定

微信 code 必须先在事务外兑换，避免持有数据库连接和行锁等待外部网络。

```text
兑换 code 得到 openid
  → 查询 app_config.initialized
  ├─ false
  │   └─ HTTP 200 NEED_BOOTSTRAP
  └─ true
      → 按 openid 查询用户和固定账本成员关系
      ├─ 无用户、DELETED、无成员、REMOVED 或 LEFT
      │   └─ HTTP 200 INVITE_REQUIRED
      ├─ 用户 DISABLED
      │   └─ HTTP 403 USER_DISABLED
      └─ 用户 ACTIVE + 成员 ACTIVE + 账本 ACTIVE
          → 开启事务
          → 撤销该用户全部有效会话
          → 更新 last_login_at
          → 插入新会话摘要
          → 追加 AUTH_LOGIN 审计
          → 提交后返回原始 Token
```

成员角色从 `ledger_member.role` 加载，不从客户端参数或 Token 内容加载。

## 8. 初始化事务

### 8.1 事务外步骤

1. 校验请求字段长度。
2. 使用新微信 code 调用 `code2Session`。
3. 只保留 `openid` 和可选 `unionid`。
4. 生成候选原始 Token、摘要和到期时间；事务失败时直接丢弃候选 Token。

### 8.2 单个数据库事务内步骤

1. `SELECT id, initialized, max_users FROM app_config WHERE id = 1 FOR UPDATE`。
2. 若 `initialized = true`，返回 409 `ALREADY_INITIALIZED`。
3. 若未配置初始化口令，返回 503 `BOOTSTRAP_NOT_CONFIGURED`。
4. 分别对配置口令和用户输入计算 SHA-256，再通过 `MessageDigest.isEqual` 常量时间比较固定长度字节。
5. 口令错误时返回 403 `BOOTSTRAP_KEY_INVALID`，事务不产生写入。
6. 插入 `app_user`，默认昵称“微信用户”，状态 `ACTIVE`。
7. 插入 `ledger(id=1)`，名称“共享账本”、币种 `CNY`、时区 `Asia/Shanghai`、所有者为新用户，`max_members` 使用已锁定 `app_config.max_users` 的值。
8. 插入 OWNER `ledger_member`。
9. 插入 15 个系统默认分类。
10. 插入 4 个默认资金账户。
11. 更新 `app_config.initialized=true`，并递增版本。
12. 撤销新用户可能存在的旧会话，再插入新会话摘要。
13. 追加 `SYSTEM_BOOTSTRAP` 审计，只记录默认数据数量，不记录微信 code、口令、Token、openid 或 `session_key`。
14. 提交事务后返回原始 Token。

两个并发初始化请求都必须锁定同一行。第一个提交后，第二个取得锁并读到 `initialized=true`，返回 409，不创建第二个用户。

### 8.3 默认分类

支出分类，共 9 个：

```text
餐饮、交通、购物、居住、医疗、教育、娱乐、人情、其他
```

收入分类，共 6 个：

```text
工资、奖金、理财、红包、退款、其他
```

`sort_no` 从 10 开始按 10 递增，`system_default=true`，状态 `ACTIVE`。

### 8.4 默认资金账户

| 名称 | `account_type` | `sort_no` |
|---|---|---:|
| 微信 | `WECHAT` | 10 |
| 支付宝 | `ALIPAY` | 20 |
| 现金 | `CASH` | 30 |
| 银行卡 | `BANK` | 40 |

初始余额均为 `0.00`，状态 `ACTIVE`。

## 9. 会话 Token 设计

### 9.1 原始 Token

- 使用 `SecureRandom` 生成 32 字节随机数。
- 使用 Base64URL 无填充编码，通常为 43 个字符。
- 原始 Token 只存在于当前请求内存和成功响应中，不写数据库、不写日志、不写审计。

### 9.2 摘要

```text
token_hash = lowercase_hex(HMAC-SHA256(key = APP_TOKEN_PEPPER, message = rawToken UTF-8 bytes))
```

数据库现有 `auth_session.token_hash CHAR(64) ... ascii_bin` 可直接保存该摘要，无需修改 V1 表结构。

### 9.3 单会话轮换

成功登录或初始化时执行：

```sql
UPDATE auth_session
SET revoked_at = CURRENT_TIMESTAMP(3)
WHERE user_id = ?
  AND revoked_at IS NULL;
```

随后插入新会话。退出按当前 Token 摘要撤销一行。

### 9.4 请求认证

`DatabaseSessionTokenVerifier` 对原始 Bearer Token 计算 HMAC 摘要，并通过一次查询连接：

```text
auth_session
  → app_user
  → ledger_member
  → ledger
```

只有同时满足以下条件才返回 `CurrentUser`：

- 会话未撤销且未过期。
- 用户状态为 `ACTIVE`。
- 成员状态为 `ACTIVE`。
- 账本状态为 `ACTIVE`。
- 成员属于固定账本 `id=1`。

因此，即使成员管理操作尚未来得及批量写 `revoked_at`，被停用或移除成员的旧 Token 也会立即认证失败。

## 10. 错误处理

### 10.1 微信错误

| 微信或网络场景 | HTTP | 业务错误码 |
|---|---:|---|
| `40029` code 无效 | 400 | `WECHAT_CODE_INVALID` |
| `40226` 登录被拦截 | 403 | `WECHAT_LOGIN_BLOCKED` |
| `-1` 系统繁忙 | 503 | `WECHAT_SERVICE_UNAVAILABLE` |
| `45011` 分钟调用额度耗尽 | 503 | `WECHAT_SERVICE_UNAVAILABLE` |
| 连接或读取超时 | 504 | `WECHAT_SERVICE_TIMEOUT` |
| 其他微信错误码 | 502 | `WECHAT_SERVICE_ERROR` |
| HTTP 非成功或响应缺少 `openid` | 502 | `WECHAT_SERVICE_ERROR` |

异常日志只记录 `requestId`、异常类型和安全的微信错误码，不记录错误消息中的 URL、请求参数、code 或 AppSecret。

### 10.2 业务错误

| 场景 | HTTP | 业务错误码 |
|---|---:|---|
| 初始化口令未配置 | 503 | `BOOTSTRAP_NOT_CONFIGURED` |
| 初始化口令错误 | 403 | `BOOTSTRAP_KEY_INVALID` |
| 已经完成初始化 | 409 | `ALREADY_INITIALIZED` |
| 用户被停用 | 403 | `USER_DISABLED` |
| Token 无效、过期、撤销或成员无效 | 401 | `AUTHENTICATION_REQUIRED` |
| 资料字段不合法 | 400 | `VALIDATION_FAILED` |

## 11. 审计设计

写入以下动作：

| 动作 | 资源 | 详情边界 |
|---|---|---|
| `SYSTEM_BOOTSTRAP` | `LEDGER` | 默认分类数、账户数 |
| `AUTH_LOGIN` | `AUTH_SESSION` | 不记录 Token 或微信身份值 |
| `AUTH_LOGOUT` | `AUTH_SESSION` | 不记录 Token 摘要 |
| `USER_PROFILE_UPDATE` | `APP_USER` | 只记录昵称/头像是否变化，不复制实际值 |

未初始化时没有合法 `user_id`，因此错误初始化口令不写 `audit_log`；只输出不含敏感值的安全日志。公开部署时使用仓库现有 Nginx 模板对 `/api/v1/auth/bootstrap` 单独限流，本任务不修改服务器运行状态。

## 12. 测试设计

### 12.1 单元测试

- 32 字节 Token 编码和 HMAC 摘要。
- 相同 Token 与 pepper 得到相同摘要，不同 Token 或 pepper 得到不同摘要。
- 数据访问参数中不出现原始 Token。
- 正确、错误、空缺初始化口令分支。
- 登录四种业务状态和单会话轮换。
- 资料更新校验和敏感审计详情边界。

### 12.2 微信客户端合同测试

使用本地 Stub HTTP 服务或 Spring Mock HTTP 基础设施测试：

- 成功返回 `openid` 和可选 `unionid`。
- 响应包含 `session_key` 时业务对象不保留它。
- `40029`、`40226`、`-1`、`45011` 和未知错误码映射。
- HTTP 非成功、无效 JSON、缺少 `openid` 和超时映射。
- 测试 Stub 镜像微信官方完整响应字段，不访问真实微信接口。

### 12.3 HTTP 流程测试

- 未初始化登录返回 `NEED_BOOTSTRAP`。
- 初始化后返回 Token，并能访问 `/users/me` 和 `/ledger`。
- 资料 PATCH 后查询返回新值。
- 重新登录后旧 Token 401、新 Token 可用。
- 退出后当前 Token 401。
- 未加入账本返回 `INVITE_REQUIRED`。
- 停用用户返回 403 `USER_DISABLED`。
- 所有失败响应保留稳定错误码和 `requestId`。

### 12.4 MySQL 隔离集成测试

只在显式提供 `DB_TEST_*` 环境变量时启用：

1. 通过 SSH 隧道连接京东云 MySQL，不使用公网 JDBC 地址。
2. 管理连接确认 `account_book_test` 不存在；存在时立即停止。
3. 创建临时测试库并执行 Flyway V1。
4. 使用 Stub `WechatSessionClient`，不访问真实微信。
5. 两个不同 openid 并发提交正确口令，断言恰好一个成功、一个 409。
6. 断言只有 1 个用户、1 个账本、1 个 OWNER、15 个分类和 4 个账户。
7. 验证初始化后口令永久失效。
8. 验证重新登录、退出、用户停用和成员移除后的 Token 行为。
9. 测试结束清理并删除 `account_book_test`。

任何清理操作都只允许使用已确认的固定测试库名；不得根据未验证变量构造删除目标，不得连接或清理生产 `account_book`。

## 13. 实施顺序

1. 为 Token 和口令安全组件编写失败测试并实现。
2. 为微信客户端错误映射编写失败测试并实现。
3. 定义 DTO、属性和 `AuthStore` 接口。
4. 为登录状态机编写失败测试并实现 `AuthService`。
5. 为初始化事务编写失败测试并实现 `BootstrapService`。
6. 实现 `JdbcAuthStore` 和数据库会话校验器。
7. 实现控制器、当前用户、资料和账本查询。
8. 编写 HTTP 流程测试。
9. 编写并执行 MySQL 隔离并发测试。
10. 执行 Maven、小程序、Nginx、Git 和敏感信息全量验证。
11. 更新 Task 4 实施记录和总执行计划。

## 14. 完成标准

- 普通登录、初始化、当前用户、资料更新、账本查询和退出接口均符合合同。
- 初始化并发只能产生一个 OWNER 和一个固定账本。
- 数据库永不保存原始 Token；代码和日志不泄露微信 code、AppSecret、`session_key`、初始化口令或原始 Token。
- 重新登录、退出、用户停用和成员移除后的旧 Token 行为通过测试。
- 所有本地测试通过，MySQL 隔离测试完成后测试库已删除。
- 生产数据库和京东云既有服务未修改。
- 所有新增开发说明均为 Markdown。

## 15. 参考资料

- [微信官方 `code2Session` 接口文档](https://developers.weixin.qq.com/miniprogram/dev/server/API/user-login/api_code2session.html)
