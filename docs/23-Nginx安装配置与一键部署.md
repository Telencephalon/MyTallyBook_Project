# MyTallyBook Nginx 安装、配置与一键部署

适用服务器：Ubuntu 22.04 64 位、4 核、16 GB、8 Mbps，公网地址 `117.72.101.42`。

目标 API 域名：`www.cr-chenny.com`。备案和证书完成前，不得把 IP 或 `:7631` 写入小程序正式配置。

## 目录约定

Ubuntu 官方包负责 Nginx 可执行文件和 systemd 服务（通常位于 `/usr/sbin/nginx`）。本项目使用 `/opt/nginx` 作为管理根目录，保存模板、安装标记和每次变更的备份；这样保留 Ubuntu 安全更新能力，同时满足项目固定目录要求。

实际生效的 server block 使用 Ubuntu 标准目录：

```text
/etc/nginx/sites-available/account-book.conf
/etc/nginx/sites-enabled/account-book.conf
/etc/nginx/snippets/account-book-proxy.inc
```

## 一键脚本

脚本：`deploy/scripts/install-nginx-prod.sh`。

从 Windows 主目录一键上传并执行可使用 `deploy/scripts/Install-NginxProd.ps1`；它同样默认 dry-run，只有显式确认才会连接服务器。

脚本默认是 dry-run，不会安装软件、写文件、改防火墙或启动服务。只有显式传入 `--apply --confirm NGINX-INSTALL` 才会执行写入。

### 1. 预演

```bash
sudo bash /path/to/install-nginx-prod.sh
```

直接在服务器运行时，需保留脚本所在仓库结构（脚本相对路径下存在 `deploy/nginx/` 模板）；如果是单独上传脚本，请使用下面的 Windows 上传入口，或传入 `--template-dir` 指向三个模板所在目录。

Windows 侧预演：

```powershell
Set-Location -LiteralPath 'D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project'
& '.\deploy\scripts\Install-NginxProd.ps1'
```

确认维护窗口后，执行实际安装：

```powershell
& '.\deploy\scripts\Install-NginxProd.ps1' -Mode local -Apply -ConfirmText NGINX-INSTALL
```

### 2. 备案/证书前：回环配置

此模式只监听 `127.0.0.1:8081`，不会占用现有公网 `80`，用于验证 Nginx 到后端 `127.0.0.1:7631` 的连通性：

```bash
sudo bash /path/to/install-nginx-prod.sh \
  --apply --confirm NGINX-INSTALL \
  --domain www.cr-chenny.com \
  --server-ip 117.72.101.42 \
  --mode local
```

### 3. 备案、DNS、证书完成后：HTTPS

先确认 DNS 和证书：

```bash
getent ahostsv4 www.cr-chenny.com
sudo test -s /etc/letsencrypt/live/www.cr-chenny.com/fullchain.pem
sudo test -s /etc/letsencrypt/live/www.cr-chenny.com/privkey.pem
```

再切换：

```bash
sudo bash /path/to/install-nginx-prod.sh \
  --apply --confirm NGINX-INSTALL \
  --domain www.cr-chenny.com \
  --server-ip 117.72.101.42 \
  --mode https
```

HTTPS 模式只监听 `443`，反代到 `127.0.0.1:7631`。脚本不会申请证书，也不会使用 HTTP-01；证书应通过 DNS-01 或云证书导入完成。

## 安全边界

- 只接受 Ubuntu 22.04，apply 必须 root。
- 默认 dry-run；写入需要明确确认字符串。
- 临时阻断软件包 post-install 自动启动，避免默认站点抢占既有 `80`；不停止或修改现有 80 服务。
- 写入前备份站点、代理片段和 default site；`nginx -t` 失败会恢复本次变更。
- 不开放 `7631`、`8081` 或 `3306`；云安全组/UFW 仍需单独限制为公网仅 443。
- 证书文件不存在时直接失败，不会启用无证书配置。
- 每次备份保存在 `/opt/nginx/backups/<UTC 时间>/`。
- Windows 上传脚本只向临时目录传输安装器和模板，远端安装完成后不依赖该临时目录。

## 验收

```bash
sudo nginx -t
sudo systemctl is-enabled nginx
sudo systemctl is-active nginx
curl -fsS http://127.0.0.1:8081/nginx-health       # local 模式
curl -fsS https://www.cr-chenny.com/nginx-health   # https 模式
sudo ss -lntp | grep -E ':(443|7631|8081|3306)\\b'
```

预期：HTTPS 模式有公网 `443` 和后端回环监听；`7631/8081/3306` 不应对公网监听。既有 `80` 服务保持原状。

## 回滚

1. 保留 `/opt/nginx/backups/` 中本次时间目录。
2. 恢复其中的 `account-book.conf`、`account-book-proxy.inc` 和 `enabled-target` 到 `/etc/nginx/`。
3. 执行 `sudo nginx -t`，通过后执行 `sudo systemctl reload nginx`。
4. 不要手工删除 `/opt/nginx/backups`；如需卸载，先确认没有其他站点依赖再使用 Ubuntu 包管理器。

## 与小程序和正式发布的衔接

只有 DNS 生效、证书可信、微信 request 合法域名配置完成后，才把小程序 trial/release 地址设为：

```text
https://www.cr-chenny.com
```

随后关闭开发工具域名校验，完成 Mate 70 Pro 和 iPhone 15 真机验收，再执行 `deploy/scripts/deploy.ps1` 的受控发布。

## 官方参考

- [Ubuntu：安装 Nginx](https://ubuntu.com/server/docs/how-to/web-services/install-nginx/)
- [Nginx：HTTPS 服务器配置](https://nginx.org/en/docs/http/configuring_https_servers.html)
