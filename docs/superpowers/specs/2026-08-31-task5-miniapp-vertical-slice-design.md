# Task 5 小程序首个纵向切片设计规格

**日期：** 2026-08-31  
**状态：** 方案 B 与本文档均已由项目所有者确认  
**范围：** 微信登录、唯一所有者初始化、会话恢复、固定账本首页、昵称资料维护和退出登录

## 1. 目标

在现有 Spring Boot Task 4 接口合同之上，将微信原生 TypeScript 小程序从官方 quickstart 骨架改造成首个可运行纵向切片：用户打开小程序后可以自动恢复会话或完成微信登录；系统未初始化时可以输入一次性初始化口令成为所有者；认证成功后可以查看固定共享账本和本人身份；可以修改昵称并安全退出。

本任务修改本地小程序与 Markdown 文档，并按已确认兼容方案让现有昵称更新控制器同时接受 `PATCH` 与 `PUT`；不修改京东云服务器、MySQL、Nginx、其他 Spring Boot 合同、生产网络或现有服务。

## 2. 已确认决策

1. 采用方案 B：微信原生组件、统一 HTTP 请求层、轻量会话仓库。
2. 不引入第三方 UI 框架或运行时状态管理框架。
3. Token 只保存在小程序本机 Storage，不进入 URL、日志或页面数据展示。
4. 个人资料页本阶段只允许修改昵称。
5. 头像只显示后端已有 HTTPS 地址或本地默认图，不增加文件上传接口、对象存储或外部头像地址输入框。
6. `INVITE_REQUIRED` 本阶段只显示等待邀请提示；邀请码和邀请链接加入流程属于 Task 6。
7. 本地开发 API 只允许 `http://127.0.0.1:7631`（按 2026-09-04 已确认的统一后端端口同步）；体验版和正式版在备案 HTTPS API 域名配置完成前主动停止请求并显示配置错误。
8. 不提交、不推送，也不覆盖隔离工作区中 Task 1～4 的现有改动。

## 3. 不在本任务范围内

- 邀请码生成、邀请链接解析、加入账本和成员管理。
- 分类、资金账户、账单和统计页面。
- 微信头像上传、图片压缩、文件存储和 CDN。
- 域名备案、HTTPS 证书、Nginx 或 systemd 上线操作。
- 生产数据库初始化和真实生产账号联调。
- UI 组件库、全局状态管理库、分包和自定义导航栏。

## 4. 总体架构

```text
页面层
  login / bootstrap / home / profile
          │
          ▼
业务服务层
  auth.ts / user.ts / ledger.ts
          │
          ▼
基础设施层
  http.ts ── session.ts ── env.ts
          │
          ▼
Spring Boot /api/v1
```

- 页面层只负责交互状态、表单校验、提示和导航，不直接拼接请求头或解析统一响应。
- 业务服务层声明认证、用户和账本 API，并返回强类型业务数据。
- `http.ts` 统一处理基础地址、超时、请求 ID、Bearer Token、统一响应和统一错误。
- `session.ts` 是单例轻量仓库，负责 Storage 持久化、内存态用户/账本上下文和会话清理。
- `env.ts` 根据微信 `envVersion` 选择受限的 API 地址，禁止 release/trial 默默回退到本地或公网 IP。

## 5. 页面与路由

`app.json` 页面顺序如下：

```text
pages/login/index
pages/bootstrap/index
pages/home/index
pages/profile/index
```

应用入口固定为登录页。认证完成后使用 `wx.reLaunch` 进入首页，避免返回到登录或初始化页面。首页与个人资料页之间使用 `wx.navigateTo` 和 `wx.navigateBack`。

官方 quickstart 的 `Hello World` 首页、失效的 `pages/logs/logs` 配置和不再使用的示例代码从运行路径中移除；确认无引用的 quickstart 页面文件可以删除。

### 5.1 登录页

登录页包含以下视图状态：

- `RESTORING`：正在恢复本地会话。
- `LOGGING_IN`：正在获取微信 code 并登录。
- `INVITE_REQUIRED`：系统已初始化，但当前微信用户尚未加入固定账本。
- `ERROR`：显示安全的错误说明、可追踪 `requestId` 和重试按钮。

进入页面后自动执行一次启动流程：

1. 从 Storage 读取 Token 和到期时间。
2. 若 Token 存在且未到期，并行加载 `/users/me` 与 `/ledger`。
3. 两个请求均成功时进入首页。
4. Token 不存在、已到期或接口返回 401 时清理会话，调用 `wx.login` 获取一次性 code。
5. 调用 `POST /api/v1/auth/wechat/login`。
6. 根据 `state` 导航：
   - `AUTHENTICATED`：保存 Token，加载用户与账本，进入首页。
   - `NEED_BOOTSTRAP`：进入初始化页面；不保存 code。
   - `INVITE_REQUIRED`：停留登录页并显示等待邀请说明。

同一页面实例内使用进行中 Promise/布尔门禁，禁止 `onLoad`、`onShow` 和重复点击同时发起多次登录。

### 5.2 初始化页

页面只包含一次性初始化口令输入框、提交按钮、返回登录按钮和错误区域。

提交规则：

1. 客户端先去除首尾空白并检查长度 20～256；口令不写日志、不写 Storage。
2. 每次提交都重新调用 `wx.login`，不得复用登录页取得的旧 code。
3. 调用 `POST /api/v1/auth/bootstrap`，请求体为新的 `code` 和 `bootstrapKey`。
4. 成功返回 `AUTHENTICATED` 时保存 Token，加载用户与账本并 `reLaunch` 到首页。
5. `BOOTSTRAP_KEY_INVALID` 显示口令错误，不泄露服务器配置状态。
6. `BOOTSTRAP_NOT_CONFIGURED` 显示“服务器尚未配置初始化口令”和 `requestId`。
7. `ALREADY_INITIALIZED` 清理初始化表单并返回登录页重新判断身份。

初始化口令输入使用密码模式；提交完成或离开页面时清空页面数据。

### 5.3 首页

首页只展示 Task 4 已提供的数据：

- 固定账本名称。
- 当前用户昵称或成员显示名。
- 当前角色：所有者、管理员或普通成员。
- 账本币种、时区和最大成员数。
- “个人资料”和“退出登录”操作。

本任务不伪造余额、账单数量或统计数据。后续功能以明确的“待后续任务实现”区域呈现，不能显示误导性模拟金额。

首页发现内存上下文缺失时重新加载用户和账本；加载失败按统一错误策略处理。

### 5.4 个人资料页

- 显示后端已有头像或本地默认头像。
- 显示角色和成员显示名，只读。
- 昵称输入去除首尾空白，长度限制 1～64。
- 只有昵称实际发生变化时调用微信原生支持的 `PUT /api/v1/users/me`，请求体仅包含 `nickname`；后端保留原有 PATCH 并由同一控制器方法同时提供 PUT 兼容入口。
- 成功后更新会话仓库中的用户资料并返回首页。
- 页面不提供 `chooseAvatar`、HTTPS 地址输入或头像清除功能。

## 6. 会话模型

Storage 只保存以下版本化对象：

```ts
interface PersistedSession {
  version: 1
  token: string
  expiresAt: string
}
```

固定 Storage Key 使用项目命名空间，例如 `mytallybook.session.v1`。用户资料和账本资料只存内存，启动时从服务端重新加载，避免长期缓存角色或成员状态。

会话仓库提供：

- `hydrate()`：读取并校验持久化结构与到期时间。
- `saveAuthenticated()`：原子更新内存 Token 和 Storage。
- `setContext()`：保存当前用户和账本内存态。
- `getToken()`、`getUser()`、`getLedger()`：只读访问。
- `clear()`：清除 Token、到期时间和全部内存上下文。

损坏、字段缺失、版本不支持或已到期的 Storage 数据按无会话处理并立即清除。

## 7. HTTP 与 API 合同

### 7.1 统一响应

成功响应：

```ts
interface ApiResponse<T> {
  code: 'OK'
  message: string
  data: T
  requestId: string
  timestamp: string
}
```

失败响应：

```ts
interface ApiErrorResponse {
  code: string
  message: string
  details?: unknown
  requestId: string
  timestamp: string
}
```

客户端不得假设非 2xx 一定包含合法 JSON；无法解析时使用响应头 `X-Request-Id`，仍不存在时使用本次客户端请求 ID。

### 7.2 请求规则

- API 路径统一以 `/api/v1` 开头。
- 默认超时 10 秒。
- 每次请求生成满足后端限制的 `X-Request-Id`：只含字母、数字、点、下划线和连字符，长度不超过 64。
- 需要认证的请求自动添加 `Authorization: Bearer <token>`。
- 不在 Console 输出请求体、响应 Token、初始化口令、微信 code 或 Authorization。
- `wx.request` 返回 2xx 但响应结构不符合合同，按 `INVALID_SERVER_RESPONSE` 处理。

### 7.3 统一错误对象

业务层只处理规范化错误：

```ts
interface AppError {
  kind: 'HTTP' | 'NETWORK' | 'TIMEOUT' | 'CONFIG' | 'INVALID_RESPONSE'
  code: string
  message: string
  requestId?: string
  statusCode?: number
}
```

处理规则：

- 401：会话仓库只清理一次，并 `reLaunch` 到登录页。
- 403：保留业务错误码并显示安全提示；若服务端已判定当前会话不可用，则按后续明确合同处理，不自行扩大含义。
- 409：交给页面根据业务码处理，初始化页处理 `ALREADY_INITIALIZED`。
- 网络断开或超时：保留当前会话，显示重试，不误判为退出。
- 5xx：显示服务暂不可用和 `requestId`，不显示内部异常。

为避免多个并发请求同时收到 401 后重复跳转，HTTP 层使用单次认证失效回调门禁。

## 8. 认证服务

认证服务封装：

```text
loginWithWechatCode(code)
bootstrapOwner(code, bootstrapKey)
logout()
```

微信能力封装单独负责将 `wx.login` 转为 Promise，并将“未返回 code”规范化为客户端错误。业务服务和页面不接触 AppID、AppSecret、openid 或 `session_key`。

认证成功响应必须同时包含非空 Token 和合法到期时间；否则拒绝写入 Storage。

## 9. 退出语义

1. 用户点击退出后调用 `POST /api/v1/auth/logout`。
2. 服务端成功或返回 401 时清除本地会话并回到登录页。
3. 网络失败或服务端 5xx 时不宣称退出成功，也不删除唯一可用于再次撤销的本地 Token；页面提示用户重试。
4. 页面任何日志都不得输出 Token。

## 10. 环境配置

`env.ts` 根据 `wx.getAccountInfoSync().miniProgram.envVersion` 返回配置：

| envVersion | API 地址策略 |
|---|---|
| `develop` | `http://127.0.0.1:7631` |
| `trial` | 只有显式配置备案 HTTPS 域名才允许请求 |
| `release` | 只有显式配置备案 HTTPS 域名才允许请求 |

体验版和正式版域名未配置时抛出 `CONFIG` 错误并显示明确说明。不得自动使用 `117.72.101.42`、`http://` 公网地址或后端内部端口 `7631`。

## 11. UI 与可访问性

- 使用微信原生 `view`、`text`、`input`、`button`、`image` 和 `scroll-view`。
- 统一在 `app.wxss` 定义颜色、间距、卡片、按钮、表单和错误提示基础样式。
- 所有异步按钮具备加载态和重复提交保护。
- 错误提示同时包含文本，不只依赖颜色。
- `requestId` 使用可长按复制的文本展示，便于排查。
- 初始化口令和昵称均设置明确 label，不依赖 placeholder 代替字段名称。

## 12. 测试策略

新增与 Node.js 24 和当前 TypeScript 版本兼容的开发期测试运行器，只用于本地自动化测试，不进入小程序运行包。具体版本在实施计划前通过官方包注册表核验并锁定。

优先测试纯 TypeScript 模块和 `wx` 适配边界：

1. Session Storage 正常恢复、过期、损坏和版本不支持。
2. 认证成功数据不完整时拒绝持久化。
3. HTTP 层注入 Token 和 `X-Request-Id`。
4. 2xx 非法响应、业务错误、网络错误和超时的规范化。
5. 401 只清理和跳转一次。
6. 登录三状态路由：`AUTHENTICATED`、`NEED_BOOTSTRAP`、`INVITE_REQUIRED`。
7. 初始化必须获取新 code，口令不持久化。
8. 昵称校验和仅提交变化字段。
9. 退出成功/401清理会话，网络或 5xx 保留会话。

每个行为先编写失败测试，再实现最小代码。每次变更至少执行：

```powershell
npm test
npm run typecheck
```

最终还需在微信开发者工具人工验证 Console、Network、Storage 和页面导航；自动化测试不能替代真实 `wx.login` 与模拟器联调。

## 13. 预计文件变化

```text
account-book-miniapp/
├─ miniprogram/
│  ├─ config/env.ts
│  ├─ services/http.ts
│  ├─ services/wechat.ts
│  ├─ services/auth.ts
│  ├─ services/user.ts
│  ├─ services/ledger.ts
│  ├─ store/session.ts
│  ├─ types/api.ts
│  ├─ pages/login/*
│  ├─ pages/bootstrap/*
│  ├─ pages/home/*
│  ├─ pages/profile/*
│  ├─ app.ts
│  ├─ app.json
│  └─ app.wxss
├─ tests/*
├─ package.json
├─ package-lock.json
└─ tsconfig.json
```

测试目录必须从微信小程序编译输入中排除，但继续由测试运行器和 TypeScript 类型检查覆盖。

## 14. 验收标准

- 无有效本地会话时能够调用微信登录接口并正确处理三种认证状态。
- 未初始化时能够使用新微信 code 和一次性口令完成所有者初始化。
- 刷新或重新打开模拟器后能够从本地 Token 恢复并重新加载用户与账本。
- 首页只显示真实用户与账本数据，不显示虚构统计。
- 昵称修改成功后首页同步显示新值，页面不提供头像上传。
- 退出成功后 Storage 中不再存在 Token，并回到登录页。
- 所有可追踪失败显示 `requestId`，敏感数据不出现在 Console、Storage 额外字段或 Markdown。
- 自动化测试和 `npm run typecheck` 全部通过。
- 微信开发者工具 Console 无未处理异常，Network 请求头符合合同，Storage 只存在版本化会话对象。
- Spring Boot、生产数据库、京东云服务和既有端口均未修改。
