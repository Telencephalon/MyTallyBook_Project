# Task 6：邀请、成员、角色与所有权转让设计规格

**日期：** 2026-09-05  
**状态：** 用户已确认按此规格实施（2026-09-05），包括第 2.2 节；进入实施与离线验证，不代表已完成。
**设计基线：** [完整设计](../../03-微信共享记账小程序完整开发设计方案.md)、[最终执行计划 Task 6](../../07-微信共享记账小程序最终开发执行计划.md)、[已确认边界](../../15-邀请与成员模块开发前边界确认.md)。  
**工作区：** `codex/mvp-development`，保留未提交改动，不提交或推送。

## 1. 目标、范围与进入条件

在已有微信登录、数据库会话、固定账本与审计基础上，完成“创建邀请 → 新成员加入 → 成员管理 → 所有权转让”的小程序纵向功能。固定一个账本，最多 10 名有效成员；不增加数据库、Redis、Docker 或服务。

包含：邀请创建/列表/撤销/接受、成员列表、任免管理员、移除/主动退出、所有权转让，以及对应小程序页面、安全校验和测试。

不包含：账单、统计、分类/账户管理、头像上传、成员别名编辑、账号停用/恢复/删除管理、账本基础资料编辑、生产部署。虽然总设计列有 `PATCH /ledger`，它不属于本轮 Task 6。

Task 5 人工证据已取得：真实登录、正确口令初始化、首页、身份/资料入口和昵称修改；本次用户进一步确认退出登录及会话恢复正常，并明确批准本规格实施。其余 Network/Storage/完整 Console 等保留为发布前补验项，不推断为通过，不通过清库或重置初始化状态补验。

## 2. 业务规则：已确认与新增建议分开

### 2.1 用户已经确认，不再重复决策

1. 转让成功后，原 OWNER 变为 MEMBER；新 OWNER 必须是本账本的另一名有效成员。
2. OWNER 如需移除 ADMIN，先降为 MEMBER 再移除。ADMIN/MEMBER 可以主动退出；OWNER 必须先转让。主动退出记 LEFT，他人移除记 REMOVED；撤销目标用户全部会话、释放名额、保留历史数据。
3. REMOVED/LEFT 成员凭新邀请重新加入时复用原成员关系，角色统一 MEMBER；DISABLED/DELETED 用户不由邀请自动恢复。
4. 已是有效成员者误用邀请返回“已是账本成员”，不消费邀请、不创建成员、不替换会话。

### 2.2 本次规格新增、用户已确认的安全规则

**已确认：邀请创建者失去 OWNER/ADMIN 管理权限时，其尚未使用且尚未过期的邀请一并撤销。** 覆盖管理员降级、管理员主动退出，以及转让后旧 OWNER 降为 MEMBER。已 USED 的邀请不改写；已过期邀请继续显示 EXPIRED。

这样避免已离开或被降权者此前发出的卡片继续新增成员。另一种做法是让邀请独立存续到到期，但那会在降权后保留原授权的效力。本规则是在本次详细规格审阅中单独获准，不追溯记为最早四条规则的一部分。

下文有关创建者资格、自动撤销及其测试统一实施，不只关闭自动撤销却保留接受端的隐含限制。

## 3. 实现选择与责任划分

比较两种适用于现有 MySQL 的方案：

- **推荐：单例配置行作为统一写事务锁。** 复用 `app_config(id=1)` 的 `FOR UPDATE`，串行化本阶段身份/成员写入；适合 10 人规模，易证明名额与权限不变量。登录、退出、资料更新也进入同一锁，以避免隐含外键锁造成顺序反转。
- **不选：按邀请、成员和会话分别加细粒度锁。** 可提高并发，但必须处理共享名额、多个 OWNER 请求和审计外键锁的交叉；当前规模没有值得承担该复杂度的吞吐需求。

后端责任：

| 单元 | 责任 |
|---|---|
| `invite` 的 Controller/DTO | 参数验证、接口封装，不放事务或微信网络逻辑 |
| `InviteService` | 输入标准化、微信兑换、邀请令牌生成/摘要、调用事务服务 |
| `InviteTransactionService` | 创建/撤销/接受邀请的原子业务与审计 |
| `member` 的 Controller/Service | 成员列表、角色修改、移除/退出、所有权转让 |
| `JdbcInviteStore` / `JdbcMemberStore` | 明确 SQL、锁定读、条件更新和受影响行数校验 |
| 现有 `AuthStore` / `JdbcAuthStore` | 复用配置锁、用户和会话操作，新增最小锁定身份查询 |
| 现有 `AuthTransactionService` / `UserService` | 接入统一写锁、事务内重验身份，不改变正常登录/资料 API 合同 |
| 现有 `AuditLogService` | 同一事务末尾写白名单审计；增加邀请敏感字段脱敏防御 |

仍使用 JdbcTemplate 强事务路径；不引入另一套认证、Token、HTTP 包装或数据库连接池。公共锁封装如抽取，必须要求调用方已处于事务中，不能先取得锁后在方法返回时释放再做业务。

## 4. API 合同

沿用 `ApiResponse<T>`、安全错误、`X-Request-Id` 和 Bearer 认证；不返回 JPA Entity。成功统一 HTTP 200（与当前接口一致）。时间为 UTC ISO-8601 字符串；数据库使用毫秒精度 UTC，过期以服务端时钟为准。

路径中的 ID 为正整数，拒绝超出 JavaScript 安全整数范围的 ID；保持当前项目数字 ID 合同，不在本阶段全局迁移为字符串。

### 4.1 邀请

| 方法与路径 | 权限 | 输入 | `data` |
|---|---|---|---|
| `POST /api/v1/invites` | OWNER/ADMIN | `{ expiresInHours?: integer }`，省略 24，范围 1～168；不接收 role/ledgerId/createdBy | `{ id, token, expiresAt, status: 'ACTIVE' }`；token 只在这一次返回 |
| `GET /api/v1/invites` | OWNER/ADMIN | `page` 默认 1、`pageSize` 默认 20/最大 50；可选 status=ACTIVE/USED/REVOKED/EXPIRED | `{ items, page, pageSize, total }` |
| `DELETE /api/v1/invites/{inviteId}` | OWNER/ADMIN | 无 body | `{ id, status }` |
| `POST /api/v1/auth/invites/accept` | 无需已有应用会话，必须有真实微信 code | `{ code, inviteToken }` | 与现有认证成功相同：`{ state: 'AUTHENTICATED', token, expiresAt }` |

列表项目仅含 `id, createdBy, createdByName, createdAt, expiresAt, status, usedBy, usedAt`；`createdByName` 使用当前昵称，已删除账号仍不暴露微信身份。未使用时 usedBy/usedAt 为 null。列表查询本固定账本全部邀请，不局限“本人创建”；不返回令牌原文或摘要。按 `created_at DESC, id DESC` 确定性排序。

定义“有效状态”，列表展示、筛选和接受接口采用同一优先级：已落库 USED/REVOKED/EXPIRED 保持原状态；其余 ACTIVE 行若 `expires_at <= decisionNow` 则为 EXPIRED；未过期但创建者已非本账本有效 OWNER/ADMIN 则为 REVOKED；其余为 ACTIVE。创建者资格包含账号、成员及账本均有效。GET 只计算，不写数据库、不启动定时任务，每次请求使用一个固定的 decisionNow。正常降权路径必须在同一事务实际落库 REVOKED，因此以后重新任命管理员不会复活旧邀请；读取时的资格检查只是异常残留数据的防御，不代替降权事务。

撤销 ACTIVE 未过期邀请：原子更新 REVOKED；重复撤销 REVOKED 返回原状态，不追加第二条审计。USED 返回 409；到期返回 410；不存在或不属于固定账本返回 404。不从列表提供“重新获取原码”功能；原码丢失需撤销并创建新邀请。

上段的重复撤销以已落库 REVOKED 为准；若仍存储 ACTIVE、仅因创建者失权而计算为 REVOKED，显式撤销接口在锁内将其落库 REVOKED 并审计一次。列表不承担这种修复。

创建/接受不做自动网络重试，也不新增幂等请求表。创建成功但客户端未收到原码时，列表仍可撤销该邀请；接受成功但响应丢失时，通过普通微信登录恢复，不再次消费邀请。

### 4.2 成员

| 方法与路径 | 权限与规则 | 输入 | `data` |
|---|---|---|---|
| `GET /api/v1/members` | 任意有效成员；本阶段仅列出有效成员 | 无 | `{ items, activeCount, maxMembers, ownerUserId }` |
| `PATCH/PUT /api/v1/members/{memberId}/role` | 仅 OWNER，对另一名有效 ADMIN/MEMBER | `{ role: 'ADMIN' \| 'MEMBER' }` | 更新后的 MemberView |
| `DELETE /api/v1/members/{memberId}` | 他人移除：OWNER/ADMIN 仅对 MEMBER；自退：仅 ADMIN/MEMBER | 无 | `{ memberId, status: 'REMOVED' \| 'LEFT' }` |
| `POST /api/v1/members/{memberId}/transfer-ownership` | 仅当前 OWNER，目标是另一名有效成员 | 无 | `{ ownerUserId, previousOwnerUserId }` |

`MemberView` 字段：`memberId, userId, nickname, displayName, role, joinedAt`。displayName 允许 null，小程序延续“成员别名为空则展示昵称”，不把昵称写回成员别名。不能返回 OpenID、UnionID、Token/摘要、会话列表或手机号。

成员按 `joined_at ASC, member_id ASC` 排序；activeCount 与名额计算均为 `app_user.status='ACTIVE' AND ledger_member.status='ACTIVE'`。列表读取账本与成员使用同一个只读一致性快照：采用单条查询，或明确限定本次只读事务为 REPEATABLE READ；不能假定多个 READ COMMITTED 查询共享快照，也不取得全局写锁。有效上限为 `min(10, app_config.max_users, ledger.max_members)`；配置缺失或超出 1～10 时安全失败，不自动改配置或踢人。

角色更新同时兼容 PATCH/PUT，共用同一个处理方法；小程序采用 PUT，沿用已有昵称更新的兼容方式。不允许通过该接口指定 OWNER、修改自身角色、修改成员别名或恢复成员状态。同角色请求为无副作用成功，不重复审计。

### 4.3 新错误与优先级

| 业务情况 | HTTP / code |
|---|---|
| 未初始化时接受邀请 | 409 `SYSTEM_NOT_INITIALIZED` |
| 用户停用 / 删除 | 403 `USER_DISABLED` / `USER_UNAVAILABLE` |
| 已是有效成员 | 409 `ALREADY_MEMBER` |
| 邀请格式合法但摘要不存在 | 404 `INVITE_INVALID` |
| 邀请到期 / 已用 / 撤销 | 410 `INVITE_EXPIRED` / 409 `INVITE_USED` / 410 `INVITE_REVOKED` |
| 成员已满 | 409 `MEMBER_LIMIT_REACHED` |
| OWNER 自退 | 409 `OWNER_TRANSFER_REQUIRED` |
| 尝试直接移除 ADMIN | 409 `ADMIN_DEMOTION_REQUIRED`（操作者已通过 OWNER 权限检查）；其他越权仍 403 |
| 目标不再有效或并发前提已变化 | 409 `MEMBER_STATE_CHANGED` |
| 固定账本配置或唯一 OWNER 不变量损坏 | 409 `LEDGER_STATE_CONFLICT`，禁止新会话签发及 Task 6 写入；退出登录例外见 6.1 |
| 锁超时/死锁导致本次事务回滚 | 409 `CONFLICT`，不返回 SQL/堆栈，也不自动重试微信 code |

其他参数错误、401、403、404、微信失败沿用已有错误。接受邀请先做形状校验及微信兑换，再在锁内检查初始化、账号禁用状态、已加入状态，然后邀请状态和名额；已加入者不需要消费或查询邀请状态。错误响应不包含输入 token/code 或第三方响应体。

## 5. 令牌、审计与保密

- 邀请原文使用 SecureRandom 产生 32 字节，编码为 43 字符、无填充 Base64URL；这是邀请码本身，不另造低熵 6 位短码。输入去首尾空白后严格校验字符及长度，保持大小写。
- 数据库存储 64 位十六进制 HMAC-SHA256：密钥复用现有 Pepper，消息为 UTF-8 `invite:v1:` 加邀请原文，使邀请摘要域与现有会话摘要分离。不改动当前会话摘要算法或 Pepper。
- 不用 `SessionTokenService.issue()` 伪装邀请有效期；邀请令牌生成与会话签发分开。拒绝路径生成过的随机候选值只存在内存中，不落库。
- 关键事件：INVITE_CREATE、INVITE_REVOKE、INVITE_ACCEPT、MEMBER_ROLE_CHANGE、MEMBER_REMOVE、MEMBER_LEAVE、OWNERSHIP_TRANSFER。自动撤销邀请逐条记录 INVITE_REVOKE，附安全原因和相关成员 ID。
- 审计只用白名单 ID、旧新角色、期限、原因和计数；失败事务不留下成功审计。额外覆盖 `inviteToken`、`inviteCode`、`tokenHash` 等敏感键递归脱敏，不能将整份 DTO/请求体传入审计。
- 页面请求编号可反馈，邀请原文只按用户主动复制/分享动作传递。任何日志、文档、错误、服务端列表中均不包含它。实际数据库凭据、AppSecret、初始化口令及 Pepper 不读取、不轮换。

## 6. 事务与并发不变量

### 6.1 统一写锁覆盖范围

已有初始化使用 `AuthStore.lockAppConfig()`。所有身份/成员写事务将以此为第一项数据库操作：初始化、登录会话签发、退出、资料更新，以及 Task 6 每个写操作。纯内存校验可在锁之前；微信网络调用必须在事务之外。

Task 6 常规顺序为：

1. 锁 `app_config(id=1)` 并核对 initialized/max_users。
2. 锁 `ledger(id=1)`，核对 ACTIVE 和单账本基本状态。
3. 使用当前锁定读重验操作者的有效账号/成员关系；所有 `CurrentUser.role` 仅能用于入口快速拒绝，不能代替事务内最新角色。
4. 锁邀请及所需成员/用户行；多成员按 ID 升序。同一配置锁已串行化这些写入，任何新增路径不得绕过它先锁成员或会话。
5. 条件更新业务数据、必要的全会话撤销/新会话插入，检查受影响行数；审计最后追加，整体提交或整体回滚。

退出、资料更新无需再显式锁账本，但必须在会话/用户写入之前取得配置锁；审计 INSERT 对账本和用户的外键校验也会涉及锁。只给新成员代码加账本锁而保留旧“用户/会话 → 审计”路径，可能构成反向等待。

新会话签发及所有 Task 6 写操作（包括创建、撤销、接受邀请）都必须在配置锁内检查唯一有效 OWNER 与 ledger.owner_user_id 一致，不能仅在转让接口检查。初始化是建立该不变量的特例，提交前验证新状态。普通资料更新保留原有效身份检查，不扩展为账本修复入口。

退出登录只做减少权限的当前会话撤销：仍先锁配置行，但不因 initialized/max_users 字段或账本/OWNER 不变量异常而额外阻止撤销；沿用原鉴权、审计和事务要求。配置行本身缺失、连接失败或审计失败时仍安全失败，不绕过统一锁、不静默吞错、不声称服务端已注销。

这项锁循环判断来自当前 SQL/外键的静态推导，不是声称已在服务器复现。MySQL 官方说明外键检查会取得所检查记录的共享锁，并建议多表事务保持一致的操作顺序。[外键检查锁](https://dev.mysql.com/doc/refman/8.0/en/innodb-locks-set.html)、[死锁处理指南](https://dev.mysql.com/doc/refman/8.0/en/innodb-deadlocks-handling.html)。

不修改全局 MySQL 隔离级别或锁等待参数。名额与角色判断使用锁定当前读，不能依赖事务外快照；数据库并发测试覆盖实际 READ COMMITTED 以及 REPEATABLE READ 行为。测试连接的隔离级别变化仅限固定测试库会话，不改服务器全局设置。

### 6.2 接受邀请

1. 事务外校验 code/令牌、兑换微信身份、生成候选会话；不执行数据库写入。
2. 取得配置与账本锁，查询最新微信账号/成员：DISABLED/DELETED 拒绝，ACTIVE 有效成员返回 ALREADY_MEMBER；不签发或撤销任何会话。
3. 取得邀请行锁后重新读取 `decisionNow = clock.instant()`，核对固定账本和第 4.1 节有效状态；必须满足 `expires_at > decisionNow`。不得用等待配置锁/邀请锁之前的时间判定。创建者失权时返回 INVITE_REVOKED，与列表一致；不能靠旧创建人快照放行。
4. 锁定当前有效成员集合，计算有效人数与上限；达到上限立即回滚，邀请仍未使用。
5. 新用户插入 ACTIVE 默认“微信用户”；已有 ACTIVE 用户无关系则新增关系；REMOVED/LEFT 复用同一 memberId，置 ACTIVE/MEMBER、更新 joined_at、清除 removed_at，保留用户昵称和成员别名。
6. 撤销该用户全部旧会话（包括恢复前遗留会话），写入本次会话。条件更新邀请为 USED，同时填写 used_by/used_at；不能分事务更新三个字段。used_at 使用锁后 decisionNow；候选会话必须在写入时仍未过期，否则安全失败、不写入失效会话。
7. 写 INVITE_ACCEPT 审计，提交后返回既有认证成功合同。任何数据库/审计失败必须回滚成员、用户、会话和邀请；人数满、邀请无效时不产生残留业务数据。

创建邀请在锁内通过权限检查后取得创建时间并计算 expiresAt，不用排队前的时间扣减用户选择的有效期。撤销、自动撤销也使用取得相关锁后的统一判定时间区分已过期与未过期邀请。

### 6.3 登录与成员移除竞态

目前 `AuthService.login` 在事务外读取成员，`AuthTransactionService.authenticate` 直接使用该对象写会话。Task 6 不可照搬此快照信任。

调整为传递稳定 userId，锁内重新查询当前账号/成员/账本；原会话签发条件全部重新判断。如果移除先提交，登录返回 INVITE_REQUIRED 且不产生新会话；如果登录先提交，之后移除必须撤销刚产生的会话。停用/删除保持既有登录错误/状态合同，不增加绕过身份检查的入口。

测试必须进一步“重新加入”后再验证旧 token 仍失效：仅测试 REMOVED 期间被拒绝不足以发现历史会话复活。重复正常登录仍延续撤销该用户所有旧会话的现有策略。

### 6.4 成员权限变更

- 所有角色/移除/转让请求在锁内重新校验操作者仍有权限、目标仍有效、账本 owner_user_id 与唯一有效 OWNER 关系一致。
- 他人移除只针对 MEMBER；自退 ADMIN/MEMBER 写 LEFT。账号 app_user 不停用、不删除，历史账单引用不变，全部会话在同一事务撤销。
- ADMIN→MEMBER、ADMIN 自退及 OWNER 转让降级时，按 2.2 撤销该创建者未使用且未过期的邀请。同角色无操作请求不触发撤销。
- 转让先验证新旧用户不同且均有效，在一个事务内更新新旧 role、ledger.owner_user_id 及 ledger.version，提交前验证唯一有效 OWNER 与 owner_user_id 一致。事务内部的中间更新顺序不对其他事务暴露，不声称数据库 CHECK 自身能约束跨行唯一 OWNER。
- 转让、普通角色变更无需强制所有人重新登录；后端每次鉴权及写锁内都读取新角色。客户端成功后重新拉取用户/账本/成员上下文，显示新权限，不缓存旧角色为授权依据。
- 两个并发转让由相同旧 OWNER 发起：后到请求锁内已不再是 OWNER，必须拒绝，不能第二次转走账本。

## 7. 小程序交互

新增页面 `invite-create`、`invite-accept`、`member-list`、`member-edit`；保留已有四个页面。新增类型和邀请/成员 API 服务、独立 InviteFlow/MemberFlow，复用现有 HttpClient、SessionStore 和错误展示；不扩展 SessionStore 的持久化字段。

- 首页有效成员可进入“成员”；OWNER/ADMIN 另可进入“邀请管理”。前端按最新角色隐藏操作，后端仍负责最终授权。
- 邀请管理：默认创建 24 小时，提供 1～168 小时整数输入；列表显示状态、到期时间和创建者。创建成功后的原码仅保存在当前页面实例内存，用于主动复制或 `onShareAppMessage` 卡片；不写 Storage、日志或全局长期缓存。
- 卡片路径固定 `/pages/invite-accept/index?inviteToken=<URL编码令牌>`，不得使用任意跳转 URL。页面退出/销毁、当前身份失效时清空原码；不在分享选择器暂时隐藏页面时抢先清空。旧邀请只能撤销，不能恢复原码。
- 接受页直接接收该参数，或允许用户手动粘贴邀请码；不先经过自动普通登录页消耗流程。展示固定“加入共享账本”说明，用户点击后才获取新微信 code 并提交。每次重试重新获取 code，不保存 code。
- 参数只规范化一次并严格校验；不请求任意主机，不打印路由参数，不在无用户操作时读取剪贴板。
- 提交接受时走不带 Bearer 的专用接口；ALREADY_MEMBER 显示“前往登录/首页”，不覆盖已有会话。成功使用现有会话保存和上下文刷新逻辑，再 reLaunch 首页；其他 4xx/5xx 不清除原有会话。
- 成员列表显示昵称/别名、角色、加入时间和名额；不伪造历史成员页或成员统计。OWNER 的管理页可任免管理员、移除普通成员和转让；管理员仅可移除普通成员。转让/移除/主动退出需要二次确认，转让提示“你将成为普通成员”。
- 主动退出成功清除本机会话并返回登录页；“退出账本”与现有“退出登录”是不同按钮和不同 API，不能混用。网络失败不擅自清除会话，也不自动重放写请求。
- 本地仍为 `127.0.0.1:7631`；trial/release 未配置备案 HTTPS API 时禁止请求。本阶段本地模拟器不能证明跨手机分享/多人真机通过，后者保留到合规 HTTPS 环境再验收，不开放公网 7631/3306 解决联调。

## 8. 测试与验收

### 离线自动化

- 邀请令牌随机长度、编码、摘要域分离，明文不写仓储/审计；既有 Pepper/会话哈希不变化。
- 参数、有效期 1/24/168 小时与越界、正整数 ID、分页、三角色 API、PUT/PATCH 同合同、不同失效错误。
- 冻结/推进 Clock 验证过期边界（等于到期即失效）、等待锁期间到期、列表筛选与接受端对创建者失权的状态一致；读取列表无写副作用。
- 配置/唯一 OWNER 异常时所有新会话签发和 Task 6 写入拒绝；配置行存在时，退出登录不因该业务不变量检查被阻止，配置行缺失或审计失败不虚报退出成功。
- 新增/重复撤销、同角色 no-op、拒绝自任 OWNER、管理员移除管理员越权、转让旧角色 MEMBER、重新加入不恢复 ADMIN、ALREADY_MEMBER 不消耗/不换会话。
- 真实小程序页面/流程测试：邀请参数、手动输入、分享/复制仅用户触发、销毁清空、每次提交新 code、防重复点击、成功保存会话、失败保留会话、动态权限和二次确认、主动退出与退出登录区分。
- 在既有完整回归上追加测试和两套 TypeScript 检查；mock 仅放微信网络和平台边界，不用 mock 证明数据库锁正确。

### 固定 MySQL 测试库门禁（单独确认执行）

继续使用既有 `MysqlTestDatabaseSupport`：固定 `account_book_test`、`account_book_test_app`、`127.0.0.1:13306` 和 `DB_TEST_RESET_ALLOWED=account_book_test`，保留身份/目标检查。各套件使用同一个 JUnit schema 资源锁，不得并行争用 schema；该锁仅协调同一测试运行，不是服务器跨进程互斥，实际门禁必须独占测试窗口、不能另起测试进程。内部并发仅用于同一套件的业务请求。缺少专用测试凭据则明确标记跳过，不拿开发库加密配置替代。

必须验证的真实事务场景：

1. 第 10 人成功、第 11 人失败；两个不同邀请争最后一个名额恰有一人成功，失败方邀请不消费且无残留。
2. 同一邀请由两人并发接受，仅一次 USED；used_by/used_at 和成员/会话一致。
3. 捕获旧 ACTIVE 登录快照→移除→尝试签发→重新邀请加入：旧 token 不能复活。
4. 旧管理员 REMOVED/LEFT 后重新加入，复用 memberId，角色 MEMBER，历史会话全部失效。
5. 两个并发转让只允许一次；转让与目标移除竞争后，始终存在与账本字段一致的唯一有效 OWNER。
6. 角色降级与创建/接受邀请竞争，结果符合锁的串行顺序和 2.2 规则。
7. 资料更新与移除/转让、退出登录与移除并发均有界完成，不能出现审计外键反向锁导致的回归。
8. 数据库写或审计注入失败时整体回滚，用户/成员/邀请/会话/成功审计无部分状态。
9. 邀请在等待锁期间到期，取得锁后必须拒绝；正常降权撤销落库后再次晋升，不得恢复旧邀请效力。

使用 latch/barrier、独立连接和有上限的 Future 等待构造确定性交错；只同时开始两个线程不等同于覆盖指定竞态。不在生产类中加入仅供测试的钩子，可在测试包装仓储边界或控制连接锁。

### 人工与发布边界

Task 5 会话恢复、退出已获用户确认，其余发布前人工清单仍需补验。Task 6 离线及真实 MySQL 门禁之后，才申请更新本地运行 JAR并进行角色/邀请人工联调；未经确认不得覆盖当前运行 JAR或重启服务。多人分享必须注明模拟器与真机、环境和实际覆盖身份，不以一个 OWNER 页面代替三角色验证。

本规格不授权清理开发库/生产库、重新初始化、数据库迁移、密码变更、密钥读取/轮换、服务冲突时强停、云端部署或 Git 提交。已应用的 V1/V2 不可修改；若实现暴露必须新增结构的缺口，停下另行确认，不绕过 schema 验证。

## 9. 下一步

按已确认规格编写可执行实现计划，随后按测试驱动开发及独立审查推进。实际完成项与验证结果记录在实施计划及实施记录中；规格确认不等于 Task 6 代码或真实数据库测试已完成。
