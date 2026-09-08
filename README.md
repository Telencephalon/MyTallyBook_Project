# MyTallyBook Project

10 人以内使用的微信共享记账小程序。

## 当前状态

> 2026-09-08 当前进度：只在主目录 `D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project` 编辑、测试和运行，旧隔离目录仅保留历史归档。Task 9 首页与统计代码、两级独立复核完成：后端 344 项通过/26 门禁跳过，小程序 244 项、两套类型检查与 17 页原生 WXML 编译通过。已授权的后端更新在启动互斥锁阶段被旧启动窗口拦住，尚未停止旧服务或替换 JAR；需先解除旧管理员 PowerShell 的“选择”状态后继续检查。真实 MySQL 统计专项与人工验收未完成。详见 [Task 8/9 实施记录](docs/18-收支记账与首页统计实施记录.md)。

> 2026-09-08 运行目录切换：Task 1–8 已交付的代码已归并到 `D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project`，主目录后端已启动并健康 UP。Task 9 尚未开发；下方带日期内容保留为历史。日常只使用本页主目录启动命令，切勿同时运行旧 worktree 后端。详见 [归并记录](docs/19-主目录归并与运行目录切换记录.md)。

- 微信小程序主体认证已完成，小程序备案处于管局审核阶段。
- 已有域名，域名 ICP 备案尚未完成。
- 京东云 Ubuntu 22.04.3 LTS 与 MySQL 8.0.46 已在 `117.72.101.42` 上可用。
- 固定测试库真实 MySQL 门禁 10/10 通过，开发库 V2 独立人工验收及备份恢复演练已完成；不恢复、不重跑迁移。2026-09-05 19:34 启动记录：微信响应类型兼容修复通过 60 项定向测试；本地加密凭据保存/复用通过 15 项新测试、29 项启动回归和 8 项口令显示回归。AppID 保留项目配置，AppSecret/开发库密码已由用户隐藏输入并加密保存，原初始化口令/Pepper 不变。SSH 已按原配置恢复，后端健康检查 HTTP 200、UP；后续真实登录验收见下条，尚未上线。当前操作见 [docs/14](docs/14-本地后端启动与加密密钥使用说明.md)，迁移验收见 [docs/13](docs/13-开发库V2受控迁移执行记录.md)，备份见 [docs/12](docs/12-开发库V2迁移前预检备份与恢复方案.md)。
- Spring Boot 已完成统一响应、异常处理、请求 ID、无状态安全、微信 `code2Session`、数据库会话、唯一所有者初始化、当前用户与固定账本查询接口；邀请、成员及角色/转让后端源码已实现并通过离线审查，尚未替换运行 JAR；记账业务尚未开发。
- 2026-09-05 用户已确认真实微信登录、正确口令初始化、首页、身份/资料入口、昵称修改、退出登录与会话恢复正常；其余 Network/Storage 发布检查不推定通过。Task 6 [详细规格](docs/superpowers/specs/2026-09-05-task6-invites-members-design.md) 已全部获准。2026-09-06 本地实现与复核已完成：后端 270 项通过、19 项数据库入口跳过，小程序 160 项及两套类型检查通过，资料页并发保存状态问题已关闭。电脑重启后已按用户授权恢复原 SSH 和原后端，健康检查 HTTP 200、UP；并未更新为 Task 6 后端。真实 MySQL、新 JAR 和新功能人工联调仍待受控执行，见[实施记录](docs/16-邀请与成员模块实施记录.md)。
- Nginx 仓库模板已准备；服务器安装、HTTPS 和 systemd 尚未完成。

## 技术架构

- 微信原生小程序 + TypeScript。
- Spring Boot 4.1 + Java 21。
- MySQL 8（服务器实际小版本以 `SELECT VERSION()` 为准）。
- Nginx + HTTPS。
- Linux 原生 JAR + systemd 部署。
- 不使用 Docker、Redis、微服务和消息队列。

## 生产部署参数基线

除非后续有明确的架构变更，本项目所有生产组件统一部署在京东云 `117.72.101.42`。

| 项目 | 固定值 | 边界 |
|---|---|---|
| 京东云公网 IP | `117.72.101.42` | 用于 SSH、DNS A 记录和公网连通性验收 |
| MyTallyBook Nginx 公网入口 | `443` | 本项目只新增并监听 HTTPS 443；最终小程序只请求 HTTPS 443 |
| Spring Boot 生产服务 | `127.0.0.1:7631` | 只允许 Nginx 从本机访问，禁止公网放行 |
| MySQL 8 生产服务 | `127.0.0.1:3306` | 后端与数据库同机，JDBC 不使用公网 IP |
| 本地 MySQL 隧道 | `127.0.0.1:13306` | 通过 SSH 转发到服务器 `127.0.0.1:3306` |

`117.72.101.42:3306` 只表示 MySQL 所在的物理服务器，不表示允许公网直连。

服务器 `*:80` 是既有 `/usr/weaver` Java/Resin 服务的边界，不属于 MyTallyBook。本项目不得新增、监听、代理、重定向或为证书签发使用 80；证书仅可采用 DNS-01 或安全导入方式。备案前代理验证仅访问服务器回环 `127.0.0.1:8081`，不访问 80。

## 目录结构

```text
MyTallyBook_Project/
├── account-book-server/      # Spring Boot 后端
├── account-book-miniapp/     # 微信原生 TypeScript 小程序
├── deploy/
│   ├── mysql/                # MySQL 管理员初始化脚本
│   ├── nginx/                # Nginx 配置
│   ├── systemd/              # systemd 服务配置
│   └── scripts/              # 发布、备份和运维脚本
└── docs/                     # 需求、设计、实施和运维文档
```

## 本机日常一键启动

直接双击主目录的 [Start-Dev.cmd](Start-Dev.cmd)。它会调用现有 PowerShell 入口；默认不打包、不重启正常服务、不修改密码或密钥。只在 SSH 提示时输入服务器密码，数据库密码和 AppSecret 自动复用加密配置。

在 PowerShell 中执行，自动检查/启动原 SSH 数据库隧道和现有本地后端：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project\deploy\scripts\Start-Dev.ps1"
```

需要时仅在 SSH 窗口输入服务器密码；数据库密码和 AppSecret 自动复用本机加密配置，初始化口令/Pepper 不变。保留 SSH、后端窗口；已正常运行则复用，未知端口占用则停止。SSH 缺失但后端仍运行时，会先恢复 SSH，再等待现有后端恢复健康，不会因为缺少隧道而提前退出，也不会强制重启。超时后保留服务并报错。总启动窗口读完结果后可按键关闭，不代表后台服务已退出。

默认入口不打包、不运行测试或清库，也不自动打开开发者工具。确需更新源码和重启后端时，在主目录执行 `.\Start-Dev.cmd -UpdateBackend`，或给上述 PowerShell 命令加 `-UpdateBackend`。详细操作、单独 SSH 命令及 DBeaver 连接参数见 [启动说明](docs/14-本地后端启动与加密密钥使用说明.md#日常开发一条命令启动)。

## 后端验证

```powershell
Set-Location -LiteralPath ".\account-book-server"
$env:DB_TEST_URL = ''
$env:DB_TEST_USERNAME = ''
$env:DB_TEST_PASSWORD = ''
$env:DB_TEST_RESET_ALLOWED = ''
.\mvnw.cmd -o test
```

运行 JAR 正在使用时不执行 `clean`、`package` 或 `verify`，避免删除/替换运行文件。以上命令仅验证本地代码，真实 MySQL 门禁需要单独确认与专用测试凭据。

## 小程序验证

```powershell
Set-Location -LiteralPath ".\account-book-miniapp"
npm ci
npm test
npm run typecheck
```

体验版和正式版在备案 HTTPS API 域名配置完成前会主动阻止请求；本地开发环境只使用 `http://127.0.0.1:7631`。

## 文档

当前唯一设计基线：

- [微信共享记账小程序完整开发设计方案（V2.0）](docs/03-微信共享记账小程序完整开发设计方案.md)
- [项目版本基线与本地环境升级方案](docs/04-项目版本基线与本地环境升级方案.md)
- [开发前最终环境与配置检查报告](docs/05-开发前最终环境与配置检查报告.md)
- [京东云 Ubuntu Nginx 与 HTTPS 实施手册](docs/06-京东云Ubuntu-Nginx安装与HTTPS反向代理实施手册.md)
- [微信共享记账小程序最终开发执行计划](docs/07-微信共享记账小程序最终开发执行计划.md)
- [后端公共协议、安全与审计基础实施记录](docs/08-后端公共协议安全与审计基础实施记录.md)
- [微信登录、数据库会话与唯一所有者初始化实施记录](docs/09-微信登录会话与唯一所有者初始化实施记录.md)
- [微信小程序首个纵向切片设计规格](docs/superpowers/specs/2026-08-31-task5-miniapp-vertical-slice-design.md)
- [微信小程序首个纵向切片实施计划](docs/superpowers/plans/2026-08-31-task5-miniapp-vertical-slice-implementation-plan.md)
- [微信小程序首个纵向切片实施记录](docs/10-微信小程序首个纵向切片实施记录.md)
- [后端基础模块收敛设计](docs/superpowers/specs/2026-09-04-backend-foundation-convergence-design.md)
- [后端基础模块收敛实施计划](docs/superpowers/plans/2026-09-04-backend-foundation-convergence-implementation-plan.md)
- [后端基础模块收敛实施记录](docs/11-后端基础模块收敛实施记录.md)
- [开发库 V2 迁移前预检、备份与恢复方案](docs/12-开发库V2迁移前预检备份与恢复方案.md)
- [开发库 V2 受控迁移执行记录](docs/13-开发库V2受控迁移执行记录.md)
- [本地后端启动与加密密钥使用说明](docs/14-本地后端启动与加密密钥使用说明.md)
- [邀请与成员详细规格](docs/superpowers/specs/2026-09-05-task6-invites-members-design.md)
- [邀请与成员实施计划](docs/superpowers/plans/2026-09-05-task6-invites-members-implementation.md)
- [邀请与成员实施记录](docs/16-邀请与成员模块实施记录.md)

以下为历史阶段记录，不再作为当前执行入口：

- [项目启动与第一阶段实施步骤](docs/01-项目启动与第一阶段实施步骤.md)
- [数据库初始化与下一阶段执行计划](docs/02-数据库初始化与下一阶段执行计划.md)
- [历史完整方案](docs/记账本微信小程序完整实现方案-单机原生部署.md)

## 安全要求

AppSecret、数据库密码、登录令牌、SSL 私钥和生产环境配置不得提交到 Git。
