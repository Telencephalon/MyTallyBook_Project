# 主目录归并与运行切换实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for the operational checklist, and superpowers:finishing-a-development-branch for verification. The user explicitly requested integration into the main directory; do not create another worktree.

**Goal:** 将已验收 Task 1–8 代码与本机运行入口收敛到原主目录，不继续 Task 9。

**Architecture:** 两分支共同基线相同，交付代码尚未提交。本次是保留备份的文件级归并与运行路径切换，不宣称产生 Git merge commit。先核对已存在的转移清单及备份，不重复覆盖，再验证主目录并启动现有 JAR。

**Tech Stack:** Windows PowerShell 5.1、Java 21、Maven、微信原生小程序/TypeScript、Windows CurrentUser DPAPI。

**Spec:** 用户明确请求“帮我完成主目录归并及运行目录切换”；`docs/18-收支记账与首页统计实施记录.md` 的 Task 8 验收为前置证据。

## Global Constraints

- 主目录：`D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project`。
- 源目录：`D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project-worktrees\codex-mvp-development`；保留，不删除。
- 不清库、不运行真实库测试、不执行独立迁移/repair、不改密码、AppID、初始化口令或 Pepper。
- 不提交/推送 Git，不覆盖唯一用户配置；任何内容冲突先检查备份和差异。
- 不开启微信开发者工具命令行服务端口；被阻止时交由用户手动导入。

## 操作清单

- [x] 核对两分支 HEAD、未提交文件、359 项转移清单以及主目录归并前备份。
- [x] 核对业务代码一致；主目录 application.yml 的原配置保留，旧模板文件已备份移除。
- [x] 核对两份 DPAPI 密文、V1/V2、AppID 项目配置与源目录一致；保留主目录 private config。
- [x] 在主目录执行 `mvnw.cmd -o -Dsurefire.reportNameSuffix=main-cutover-20260908-1848 test`（清除测试子进程敏感环境）；26 项真实库门禁保持跳过。
- [x] 在主目录执行 `npm test` 和 `npm run typecheck`。
- [x] 校验 16 页注册文件完整并通过原生 WXML 编译；134 个运行包 class/资源与主目录编译结果摘要一致。
- [x] 验证 SSH 13306 归属、无旧项目后端运行；以 `-ExistingConfigurationOnly` 启动主目录 JAR。
- [x] 核对主目录 Java/JAR 进程归属、HTTP 200 / UP、Flyway V2 无需迁移；复跑主目录 `Start-Dev.ps1` 验证复用。
- [x] 更新 README、日常启动说明、总计划与实施记录，保留备份和旧目录。
- [ ] 用户在微信开发者工具导入主目录 `account-book-miniapp` 并编译。CLI 因服务端口关闭被拒绝，不能声称已自动切换 IDE 或已完成页面验收。

## 回退边界

切换失败时不覆盖原备份、不同时启动两个 JAR、不删除旧 worktree。须核对当前监听进程的精确 JAR 路径后再受控停止；确认端口释放后可从旧目录复用原配置启动。目录回退不包含数据库恢复、密钥轮换或重新初始化。Git 本地提交与分支清理留待另行决定。
