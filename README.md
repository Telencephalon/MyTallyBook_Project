# MyTallyBook Project

10 人以内使用的微信共享记账小程序。

## 当前状态

- 微信小程序主体认证已完成，小程序备案处于管局审核阶段。
- 已有域名，域名 ICP 备案尚未完成。
- 京东云 Ubuntu 22.04.3 LTS 和 MySQL 8 已可用。
- Spring Boot 工程骨架已存在，业务功能尚未开发。
- 微信原生 TypeScript 小程序骨架已创建，Node.js 24 LTS 和类型检查已配置。
- Nginx、HTTPS 和 systemd 尚未配置。

## 技术架构

- 微信原生小程序 + TypeScript。
- Spring Boot 4.1 + Java 21。
- MySQL 8.4 LTS。
- Nginx + HTTPS。
- Linux 原生 JAR + systemd 部署。
- 不使用 Docker、Redis、微服务和消息队列。

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

## 后端验证

```powershell
Set-Location -LiteralPath ".\account-book-server"
.\mvnw.cmd clean verify
```

## 文档

当前唯一设计基线：

- [微信共享记账小程序完整开发设计方案（V2.0）](docs/03-微信共享记账小程序完整开发设计方案.md)
- [项目版本基线与本地环境升级方案](docs/04-项目版本基线与本地环境升级方案.md)

以下为历史阶段记录，不再作为当前执行入口：

- [项目启动与第一阶段实施步骤](docs/01-项目启动与第一阶段实施步骤.md)
- [数据库初始化与下一阶段执行计划](docs/02-数据库初始化与下一阶段执行计划.md)
- [历史完整方案](docs/记账本微信小程序完整实现方案-单机原生部署.md)

## 安全要求

AppSecret、数据库密码、登录令牌、SSL 私钥和生产环境配置不得提交到 Git。
