# MyTallyBook Project

10 人以内使用的微信共享记账小程序。

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
├── account-book-miniapp/     # 微信原生小程序
├── deploy/
│   ├── nginx/                # Nginx 配置
│   ├── systemd/              # systemd 服务配置
│   └── scripts/              # 发布、备份和运维脚本
└── docs/                     # 需求、设计、实施和运维文档
```

## 后端验证

```powershell
Set-Location -LiteralPath ".\account-book-server"
.\mvnw.cmd test
```

## 文档

- [完整实现方案](docs/记账本微信小程序完整实现方案-单机原生部署.md)
- [项目启动与第一阶段实施步骤](docs/01-项目启动与第一阶段实施步骤.md)
- [IDEA 创建 Spring Boot 后端工程步骤](docs/02-IntelliJ-IDEA创建SpringBoot后端工程步骤.md)
- [项目初始化记录](docs/03-项目目录与Git初始化记录.md)

## 安全要求

AppSecret、数据库密码、登录令牌、SSL 私钥和生产环境配置不得提交到 Git。

