# Task 11 生产部署准备实施计划

日期：2026-09-11  
范围：仅主目录 `D:/Work/Workplaces/privateWork/AAProject/MyTallyBook_Project`。

## 目标

补齐可审计、可回滚的生产部署材料，并在不连接服务器、不修改生产、不写入真实域名或凭据的前提下完成离线验证。服务器实际变更必须等域名、证书和维护窗口分别确认后执行。

## 固定边界

- Spring Boot 只监听 `127.0.0.1:7631`；MySQL、Actuator 不对公网开放。
- Nginx 只新增 443；既有公网 80 及 `/usr/weaver` 不停止、不代理、不重启、不改配置。
- 生产密钥、数据库密码、证书私钥只存在服务器受限环境文件，不进入 Git、脚本参数、日志或聊天。
- `__API_DOMAIN__` 仅作为模板占位；不得把 `117.72.101.42:7631` 写成体验版或正式版地址。
- 发布失败必须能切回上一版本 JAR；备份恢复演练使用临时库，不碰生产数据。

## 分阶段步骤

### A. 仓库离线材料

- [ ] 明确 `application-prod.yml` 的回环地址、7631 端口和优雅停机。
- [ ] 创建非 root `accountbook` 用户运行的 `deploy/systemd/account-book.service`。
- [ ] 创建发布/校验/原子切换/回滚脚本及 Windows 编排入口。
- [ ] 创建备份脚本、保留策略和恢复演练说明。
- [ ] 为 systemd、脚本和 Nginx 模板补充静态安全检查。

### B. 服务器只读预检（后续维护窗口前）

- [ ] 只读核实 Ubuntu、既有 80、443、7631、3306、Actuator、Nginx、systemd、UFW 和安全组状态。
- [ ] 发现端口、用户、目录或服务冲突立即停止，不覆盖既有系统。

### C. 受控上线（外部条件具备后）

- [ ] 配置真实 API 域名 DNS A 记录、受信任证书和微信 request 合法域名。
- [ ] 安装受限环境文件、版本化 JAR、systemd 和 443 Nginx 配置。
- [ ] 执行发布前备份、健康检查、HTTPS/SNI 检查、公网边界检查和失败回滚演练。
- [ ] 验证每日/每周保留策略、临时库恢复和服务器重启自动恢复。

## 验证命令

仓库阶段只运行离线检查：

```powershell
Set-Location -LiteralPath 'D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project'
& '.\deploy\nginx\tests\Test-NginxTemplates.ps1'
& '.\deploy\scripts\tests\Test-Task11Artifacts.ps1'
git diff --check
```

Task 11 只有在 C 阶段全部证据齐备后才可标记完成；本计划文件本身不授权服务器操作。
