# 京东云 Ubuntu Nginx 安装与 HTTPS 反向代理实施手册

> **供执行人员使用：** 按本文复选框逐项执行；任何一步验证失败都先停止，不要跳到下一阶段。  
> **目标：** 在京东云 `117.72.101.42` 的 Ubuntu 22.04.3 上安装 Nginx，将公网 HTTPS `/api/v1/**` 安全反向代理到本机 Spring Boot `127.0.0.1:7631`。  
> **架构：** 备案前只安装 Nginx 并通过 `127.0.0.1:8081` 完成本机验证；备案、DNS 和证书完成后，Nginx 只监听公网 443，再反向代理到 `127.0.0.1:7631`。Nginx 不监听或接管现有 80，不代理 Actuator，也不公开 7631/3306。  
> **技术栈：** Ubuntu 22.04.3、Ubuntu apt Nginx、DNS-01 或导入的受信任证书、Spring Boot 4、京东云安全组、UFW。  
> **设计基线：** `docs/03-微信共享记账小程序完整开发设计方案.md`。

> **2026-08-30 服务器覆盖规则：** 当前 `*:80` 已由 `/usr/weaver` Java/Resin 系统使用。本文所有旧版“监听 80、HTTP-01、HTTP 跳转 HTTPS”做法均不得在这台服务器执行。遇到任何其他端口、目录、用户、服务名或日志路径冲突，也必须停止当前步骤，为 MyTallyBook 选择独立资源，不得停止、重启、迁移或覆盖既有服务。

## 1. 先看结论

当前域名尚未完成 ICP 备案，因此分两阶段实施：

| 阶段 | 现在是否能做 | 内容 |
|---|---|---|
| 阶段 A：服务器本机准备 | 可以立即做 | 安装 Nginx但不启用默认站点，仅用 `127.0.0.1:8081` 验证反向代理 |
| 阶段 B：正式 HTTPS | 备案和 DNS 完成后做 | 通过 DNS-01 或导入方式准备证书，只放行 443，登记微信 request 合法域名 |

安装 Nginx 本身不要求 ICP 备案。ICP备案未完成时，不要把 HTTP、IP 地址或跳过合法域名校验当成可上线状态，也不要把真实登录口令或令牌发送到未加密 HTTP 接口。

最终网络边界：

```text
微信小程序
  → https://API_DOMAIN/api/v1/**
  → 京东云安全组 443
  → Nginx 443
  → http://127.0.0.1:7631/api/v1/**
  → Spring Boot

公网禁止访问：7631、3306、Actuator
```

## 2. 本次新增的配置文件

| 仓库文件 | 安装位置 | 用途 |
|---|---|---|
| `deploy/nginx/account-book-proxy.inc` | `/etc/nginx/snippets/account-book-proxy.inc` | 公共反向代理头和超时 |
| `deploy/nginx/account-book-http.conf.template` | `/etc/nginx/sites-available/account-book.conf` | 证书签发前仅限 `127.0.0.1:8081` 的本机代理配置 |
| `deploy/nginx/account-book-https.conf.template` | `/etc/nginx/sites-available/account-book.conf` | 证书签发后的 443-only 正式 HTTPS 配置 |

模板中的 `__API_DOMAIN__` 必须替换为真实 API 子域名，例如 `api.example.com`。仓库当前没有真实域名，本文不会猜测。

## 3. 阶段 A：现在即可执行

### 任务 1：检查服务器和已有监听端口

- [ ] 登录京东云服务器后执行：

```bash
cat /etc/os-release
uname -m
sudo ss -lntp
sudo systemctl status nginx --no-pager 2>/dev/null || true
```

预期：

- 系统为 Ubuntu 22.04.3 LTS。
- 当前没有 Nginx，或者 `nginx.service` 不存在；已确认 80 由既有 Java/Resin 服务使用，必须保持不变。
- 如果 Spring Boot 已启动，7631 最终必须显示为 `127.0.0.1:7631`，不能是 `0.0.0.0:7631` 或 `[::]:7631`。
- MySQL 与后端同机时，3306 应只监听本机地址。

### 任务 2：安装 Ubuntu 官方 Nginx

Ubuntu 官方推荐可直接使用系统包安装 Nginx：[Ubuntu Nginx 安装文档](https://ubuntu.com/server/docs/how-to/web-services/install-nginx/)。不要同时混用 Ubuntu 仓库与 nginx.org 仓库。

- [ ] 执行受控安装。以下是独立的提权 `bash` 子进程，不会在调用者的交互 shell 注册 `trap`。安装前同时检查既有路径和符号链接（包括悬空链接）；任一存在均必须停止并人工确认其所属系统，绝不覆盖或删除。只有确认原路径或链接均不存在后才将 `owned=1`，使退出处理只删除本次自有的临时文件。此步骤不停止、重启或修改既有 80 进程：

```bash
sudo bash <<'POLICY_RC_INSTALL'
set -Eeuo pipefail

owned=0
cleanup_policy_rc() {
    if (( owned )); then
        rm -f -- /usr/sbin/policy-rc.d
        owned=0
    fi
}
trap cleanup_policy_rc EXIT
trap 'cleanup_policy_rc; exit 130' INT
trap 'cleanup_policy_rc; exit 143' TERM
trap 'cleanup_policy_rc; exit 129' HUP

policy_file=/usr/sbin/policy-rc.d
if test -e "$policy_file" || test -L "$policy_file"; then
    echo "$policy_file 已存在或为符号链接；不得覆盖或删除，停止安装。" >&2
    exit 1
fi

owned=1
install -m 0755 /dev/stdin /usr/sbin/policy-rc.d <<'EOF'
#!/bin/sh
exit 101
EOF

apt update
apt install -y nginx curl ca-certificates snapd dnsutils openssl

cleanup_policy_rc
trap - EXIT INT TERM HUP
POLICY_RC_INSTALL
```

安装包可能尝试用默认站点启动 Nginx 并因 80 已占用而失败；临时 `policy-rc.d` 会可靠阻止这次自动启动。`apt update` 或 `apt install` 任一步失败时，`set -Eeuo pipefail` 退出独立子进程并由 `EXIT` trap 清理；正常成功也会在命令块返回前显式清理并撤销所有 trap。这不会授权停止、重启或修改现有 Java/Resin 进程。必须先关闭 Nginx 默认站点、部署仓库中的 loopback-only 配置并通过 `nginx -t`，然后才能显式启动 Nginx。

- [ ] 检查安装结果：

```bash
nginx -v
sudo nginx -t
sudo systemctl status nginx --no-pager
```

预期：

- `nginx -t` 显示 syntax is ok 和 test is successful。
- 在 loopback-only 配置部署前，Nginx 可以是 inactive 或 failed；不得因此触碰 80 上的既有进程。

此时京东云安全组不开放 443；`127.0.0.1:8081` 的本机验证不依赖公网入口。

### 任务 3：从 Windows 上传仓库中的 Nginx 模板

- [ ] 在本地 PowerShell 执行，按提示输入服务器信息：

```powershell
Set-Location -LiteralPath 'D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project'

$ServerIp = '117.72.101.42'
$SshUser = Read-Host '请输入 SSH 用户，例如 root 或 ubuntu'
$SshPort = Read-Host '请输入 SSH 端口，例如 22'

scp -P $SshPort `
  '.\deploy\nginx\account-book-proxy.inc' `
  '.\deploy\nginx\account-book-http.conf.template' `
  '.\deploy\nginx\account-book-https.conf.template' `
  "${SshUser}@${ServerIp}:/tmp/"
```

- [ ] 回到服务器确认文件：

```bash
ls -l \
  /tmp/account-book-proxy.inc \
  /tmp/account-book-http.conf.template \
  /tmp/account-book-https.conf.template
```

### 任务 4：安装备案前 loopback-only 配置

- [ ] 输入真实 API 子域名。只输入主机名，不要带 `http://`、路径或端口：

```bash
read -rp '请输入 API 子域名，例如 api.example.com: ' API_DOMAIN

if [[ ! "$API_DOMAIN" =~ ^([A-Za-z0-9-]+\.)+[A-Za-z]{2,63}$ ]]; then
    echo '域名格式不正确；请使用类似 api.example.com 的主机名。'
    exit 1
fi

export API_DOMAIN
printf '本次配置域名：%s\n' "$API_DOMAIN"
```

- [ ] 先确认没有已上线配置。为避免误把已有 HTTPS 配置覆盖成 HTTP，发现文件已存在时本步骤主动停止：

```bash
if sudo test -e /etc/nginx/sites-available/account-book.conf; then
    echo '/etc/nginx/sites-available/account-book.conf 已存在。'
    echo '请先人工确认并备份，禁止直接重复执行阶段 A。'
    exit 1
fi
```

- [ ] 确认文件不存在后，安装代理片段和 HTTP 配置：

```bash
sudo install -d -m 0755 \
  /etc/nginx/snippets \
  /etc/nginx/sites-available \
  /etc/nginx/sites-enabled \
  /var/www/letsencrypt

sudo install -m 0644 \
  /tmp/account-book-proxy.inc \
  /etc/nginx/snippets/account-book-proxy.inc

sudo install -m 0644 \
  /tmp/account-book-http.conf.template \
  /etc/nginx/sites-available/account-book.conf

sudo sed -i "s/__API_DOMAIN__/${API_DOMAIN}/g" \
  /etc/nginx/sites-available/account-book.conf
```

- [ ] 备份并关闭 Nginx 默认站点：

```bash
if [[ -e /etc/nginx/sites-enabled/default || -L /etc/nginx/sites-enabled/default ]]; then
    sudo cp -a \
      /etc/nginx/sites-enabled/default \
      "/etc/nginx/default-site.$(date +%Y%m%d_%H%M%S).backup"
    sudo rm -f /etc/nginx/sites-enabled/default
fi

sudo ln -sfn \
  /etc/nginx/sites-available/account-book.conf \
  /etc/nginx/sites-enabled/account-book.conf
```

- [ ] 必须先测试，再显式启用并启动 Nginx；测试失败时自动恢复 Ubuntu 默认站点。此时服务此前被安装期拦截，不能使用 `reload`：

```bash
if ! sudo nginx -t; then
    echo 'account-book Nginx 配置检查失败，恢复默认站点。'
    sudo rm -f /etc/nginx/sites-enabled/account-book.conf
    if sudo test -f /etc/nginx/sites-available/default; then
        sudo ln -sfn \
          /etc/nginx/sites-available/default \
          /etc/nginx/sites-enabled/default
    fi
    sudo nginx -t || true
    exit 1
fi

sudo systemctl enable --now nginx
sudo systemctl status nginx --no-pager
```

- [ ] 仅验证本机代理入口（不得访问 80）：

```bash
curl -fsS http://127.0.0.1:8081/nginx-health

sudo ss -lntp | grep ':8081'
```

预期输出：

```text
nginx local proxy ok
```

`127.0.0.1:8081` 只用于备案前在服务器本机验证代理，禁止在 UFW 或京东云安全组中放行。

### 任务 5：确认 Spring Boot 只监听本机

仓库已包含 `account-book-server/src/main/resources/application-prod.yml`。其生产端口基线必须保持为：

```yaml
server:
  address: 127.0.0.1
  port: 7631
  forward-headers-strategy: framework
```

后续创建 systemd 服务时，必须激活 `prod` 配置，例如在 service 中增加：

```ini
Environment=SPRING_PROFILES_ACTIVE=prod
```

如果需要在 systemd service 中显式覆盖这些值，只能使用下面的等价环境变量：

```ini
Environment=SERVER_ADDRESS=127.0.0.1
Environment=SERVER_PORT=7631
Environment=SERVER_FORWARD_HEADERS_STRATEGY=framework
```

不要在 profile 和 systemd 中配置冲突值。数据库密码必须由服务器受限环境文件提供 `DB_PASSWORD`，不得写入 Git。没有 systemd 服务与后端可执行 JAR 时，“重启后自动恢复”不能视为已完成。

- [ ] Spring Boot 启动后执行：

```bash
sudo ss -lntp | grep ':7631'
curl -i http://127.0.0.1:7631/actuator/health
curl -i http://127.0.0.1:8081/api/v1/unknown
```

判断方式：

- 7631 必须监听 `127.0.0.1`。
- `/actuator/health` 只在服务器本机调用，不通过 Nginx 暴露。
- 经 8081 请求得到后端的 401、403 或 404，都能证明代理已连到后端；`502 Bad Gateway` 表示 Spring Boot 未启动、端口错误或没有监听 `127.0.0.1:7631`。

`application.yml` 已提供环境变量化的 DataSource、Flyway 和 JPA 基线配置。`502 Bad Gateway` 只表示 Nginx 已运行但后端未启动或不可达；它不能作为反向代理验收通过的证据。后端服务尚未部署或启动时可预期出现 502，但必须待后端可达并返回 401、403 或 404 后，才算代理链路验收通过。

## 4. 阶段 B：ICP备案和 DNS 完成后执行

阶段 B 往往与阶段 A 相隔数天。每次重新登录 SSH 后，都先重新设置并校验域名变量：

```bash
read -rp '请输入 API 子域名，例如 api.example.com: ' API_DOMAIN

if [[ ! "$API_DOMAIN" =~ ^([A-Za-z0-9-]+\.)+[A-Za-z]{2,63}$ ]]; then
    echo '域名格式不正确。'
    exit 1
fi

export API_DOMAIN
printf '本次操作域名：%s\n' "$API_DOMAIN"
```

### 任务 6：配置 DNS 和京东云安全组

- [ ] 在域名 DNS 中创建 API 子域名的 A 记录：

```text
记录类型：A
主机记录：api
记录值：117.72.101.42
```

- [ ] 如果没有正确配置 IPv6，删除错误的 AAAA 记录。错误 AAAA 会让部分客户端或证书验证访问错误地址。

- [ ] 在服务器上验证解析：

```bash
dig +short A "$API_DOMAIN"
dig +short AAAA "$API_DOMAIN"
```

A 记录必须返回 `117.72.101.42`。

- [ ] 在京东云控制台为该云主机绑定的安全组添加入站规则：

| 协议/端口 | 来源 | 用途 |
|---|---|---|
| TCP 443 | `0.0.0.0/0` | 小程序 HTTPS API |

京东云安全组只为 MyTallyBook 新增 TCP 443 入站规则。SSH 与既有 80 规则必须保持原样；不要为 MyTallyBook 新增或修改 80 规则，也不要添加 7631、8081 或 3306 的公网入站规则。现有 80 规则及其服务归属于既有系统。京东云安全组操作入口可参考：[京东云安全组文档](https://docs.jdcloud.com/cn/virtual-machines/security-group-overview)。

本项目模板默认只监听 IPv4。没有明确启用京东云 IPv6、IPv6 安全组并完成 IPv6 验收前，不要创建 AAAA 记录。以后需要 IPv6 时，仅为本项目增加 `[::]:443` 和对应安全组规则。

### 任务 7：保护既有 UFW 状态

MyTallyBook 不接管 UFW。先只读检查：

```bash
sudo ufw status verbose
sudo ufw status numbered
```

- [ ] 若 UFW 为 `inactive`：MyTallyBook 安装流程保持它为 inactive；不得设置默认策略、不得启用 UFW，也不得删除或重排任何现有规则。
- [ ] 若 UFW 已为 `active`：只新增 `sudo ufw allow 443/tcp`，随后再次运行上述只读检查，确认既有 SSH 与 80 规则仍在且规则顺序、默认策略未改变。
- [ ] 无论 UFW 状态如何，都必须在既有服务所有者认可的方式下验证 80 的既有行为保持不变；80 不是 MyTallyBook 的探测、验收或改动对象。

不要删除 80、SSH、7631、8081 或 3306 的既有规则；这些规则可能属于其他系统，超出本项目授权范围。

Ubuntu 官方说明 UFW 是默认的简化防火墙工具：[Ubuntu 防火墙文档](https://documentation.ubuntu.com/server/how-to/security/firewalls/index.html)。

### 任务 8：确认不占用 80 的证书方案

本服务器禁止使用 HTTP-01。必须在以下工作开始前确认域名 DNS 服务商，并选择支持自动续期的 DNS-01 客户端/插件；如果使用京东云或其他证书服务导入证书，也必须提供完整证书链、私钥和明确的续期/替换流程。不得为签发证书临时停止现有 80 服务。

- [ ] 记录域名 DNS 服务商、选定的 DNS-01 插件或证书签发平台、续期责任人和到期监控方式。
- [ ] 证书私钥只保存在服务器受限目录，不进入仓库、Markdown、聊天或截图。
- [ ] 在 DNS 服务商尚未确认前停止本任务，不猜测插件或 API 凭据格式。

Let's Encrypt 对 DNS-01 的说明见：[Let's Encrypt Challenge Types](https://letsencrypt.org/docs/challenge-types/)。

### 任务 9：通过 DNS-01 签发或导入受信任证书

下面只安装 Certbot 工具；具体 DNS 插件和签发命令必须在 DNS 服务商确认后补充并单独审核。Certbot 官方安装参考：[Certbot 指引](https://certbot.eff.org/instructions)、[Ubuntu TLS 证书文档](https://ubuntu.com/server/docs/how-to/security/obtain-tls-certificates/)。

- [ ] 避免 apt 版和 Snap 版混用，然后安装 Certbot：

```bash
sudo apt-get remove -y certbot python3-certbot-nginx 2>/dev/null || true
sudo snap install --classic certbot
sudo ln -sfn /snap/bin/certbot /usr/local/bin/certbot
certbot --version
```

- [ ] 输入实际域名和接收证书通知的邮箱：

```bash
read -rp '请输入 API 子域名，例如 api.example.com: ' API_DOMAIN
read -rp '请输入证书通知邮箱: ' LE_EMAIL

if [[ ! "$API_DOMAIN" =~ ^([A-Za-z0-9-]+\.)+[A-Za-z]{2,63}$ ]]; then
    echo '域名格式不正确。'
    exit 1
fi

if [[ ! "$LE_EMAIL" =~ ^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$ ]]; then
    echo '邮箱格式不正确。'
    exit 1
fi

export API_DOMAIN LE_EMAIL
```

- [ ] 使用已确认并已审核的 DNS-01 插件签发证书；不得使用 `--webroot`、`--standalone` 或任何需要监听 80 的方式。插件命令和 DNS API 权限必须遵循最小权限原则，并在域名服务商确认后写入独立 Markdown 操作记录。

- [ ] 检查证书：

```bash
sudo certbot certificates
sudo test -r "/etc/letsencrypt/live/${API_DOMAIN}/fullchain.pem" \
  && echo 'fullchain.pem 可读'
sudo test -r "/etc/letsencrypt/live/${API_DOMAIN}/privkey.pem" \
  && echo 'privkey.pem 可读'
```

如果签发失败，不要连续反复申请。先检查 A/AAAA、DNS TXT 传播、插件权限和 Certbot 日志，避免触发证书机构速率限制。

### 任务 10：切换到正式 HTTPS 配置

不能在证书文件尚不存在时提前启用 HTTPS 模板，否则 `nginx -t` 会因证书路径不存在而失败。

- [ ] 如果中途重新登录过 SSH，先重新执行阶段 B 开头的域名输入步骤。然后校验当前变量：

```bash
if [[ -z "${API_DOMAIN:-}" ]] \
   || [[ ! "$API_DOMAIN" =~ ^([A-Za-z0-9-]+\.)+[A-Za-z]{2,63}$ ]]; then
    echo 'API_DOMAIN 未设置或格式错误；请停止切换。'
    exit 1
fi
```

- [ ] 确保证书已经成功签发，然后执行带自动回退的配置切换：

```bash
NGINX_SITE='/etc/nginx/sites-available/account-book.conf'
NGINX_BACKUP="${NGINX_SITE}.http.$(date +%Y%m%d_%H%M%S).backup"

sudo cp "$NGINX_SITE" "$NGINX_BACKUP"
sudo install -m 0644 \
  /tmp/account-book-https.conf.template \
  "$NGINX_SITE"
sudo sed -i "s/__API_DOMAIN__/${API_DOMAIN}/g" "$NGINX_SITE"

if ! sudo nginx -t; then
    echo "HTTPS 配置检查失败，恢复：${NGINX_BACKUP}"
    sudo cp "$NGINX_BACKUP" "$NGINX_SITE"
    sudo nginx -t
    exit 1
fi

sudo systemctl reload nginx
sudo systemctl status nginx --no-pager
```

- [ ] 检查最终监听：

```bash
sudo ss -lntp | grep -E ':(80|443|7631|3306)\b'
```

正确边界：

```text
0.0.0.0:80                  既有 Java/Resin 服务（保持不变）
0.0.0.0:443                 Nginx
127.0.0.1:7631              Spring Boot
127.0.0.1:3306              MySQL（同机部署时）
```

Nginx 配置中不得出现 `listen 80` 或 `[::]:80`；不得出现 `0.0.0.0:7631`、`[::]:7631`、`0.0.0.0:3306` 或 `[::]:3306`。

### 任务 11：配置并测试证书自动续期

DNS-01 插件或证书导入流程续期后需要重新加载 Nginx，因此增加部署钩子。只有使用的 DNS 插件支持无人工交互续期时，才能把 `certbot renew --dry-run` 作为完整验收；手工 DNS-01 只能用于临时测试，不满足生产自动续期要求。

- [ ] 创建续期钩子：

```bash
sudo install -d -m 0755 /etc/letsencrypt/renewal-hooks/deploy

sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh >/dev/null <<'EOF'
#!/bin/sh
/usr/sbin/nginx -t && /bin/systemctl reload nginx
EOF

sudo chmod 0750 \
  /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
```

- [ ] 验证钩子、定时器和续期：

```bash
sudo /etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh
sudo systemctl status snap.certbot.renew.timer --no-pager
sudo systemctl list-timers --all | grep certbot
sudo certbot renew --dry-run
```

`renew --dry-run` 必须成功，才算续期链路完成。

## 5. HTTPS 与反向代理验收

### 5.1 服务器本机验收

- [ ] 执行：

```bash
sudo nginx -t
sudo systemctl is-active nginx

# 真正的服务器本机 TLS/SNI 检查，不依赖云网络回环。
curl -i --resolve "${API_DOMAIN}:443:127.0.0.1" \
  "https://${API_DOMAIN}/nginx-health"
curl -i --resolve "${API_DOMAIN}:443:127.0.0.1" \
  "https://${API_DOMAIN}/api/v1/unknown"

sudo journalctl -u nginx -n 100 --no-pager
sudo tail -n 100 /var/log/nginx/account-book.error.log
```

预期：

- HTTPS `/nginx-health` 返回 200。
- `/api/v1/unknown` 在后端正常运行时应返回后端的 401、403 或 404，而不是 Nginx 502。
- 80 端口既有应用保持原有行为，不纳入 MyTallyBook 验收。

### 5.2 证书链验收

- [ ] 执行：

```bash
echo | openssl s_client \
  -connect "${API_DOMAIN}:443" \
  -servername "$API_DOMAIN" \
  -verify_return_error 2>/dev/null \
  | openssl x509 -noout -subject -issuer -dates

echo | openssl s_client \
  -connect "${API_DOMAIN}:443" \
  -servername "$API_DOMAIN" 2>&1 \
  | grep 'Verify return code'
```

预期包含：

```text
Verify return code: 0 (ok)
```

Nginx 必须使用 `fullchain.pem`，不能只配置叶子证书 `cert.pem`。

### 5.3 从 Windows 检查公网边界

- [ ] 在本地 PowerShell 执行：

```powershell
$ServerIp = '117.72.101.42'
$ApiDomain = Read-Host '请输入 API 子域名，例如 api.example.com'

Test-NetConnection -ComputerName $ServerIp -Port 443
Test-NetConnection -ComputerName $ServerIp -Port 7631
Test-NetConnection -ComputerName $ServerIp -Port 8081
Test-NetConnection -ComputerName $ServerIp -Port 3306

curl.exe -i "https://${ApiDomain}/nginx-health"
```

预期：

- 443 的 `TcpTestSucceeded` 为 `True`。
- 7631、8081 和 3306 的 `TcpTestSucceeded` 必须为 `False`。
- HTTPS 健康检查返回 200。

## 6. 微信公众平台与开发者工具配置

HTTPS验收全部通过后再处理：

- [ ] 微信公众平台进入“小程序 → 开发管理 → 开发设置 → 服务器域名”。
- [ ] 在 `request 合法域名` 中填写：`https://实际API子域名`。
- [ ] 只填写协议和域名，不填写 `/api/v1` 路径，不添加 `:443`。
- [ ] 小程序 API 基地址改为相同的 HTTPS 域名。
- [ ] 微信开发者工具进入“详情 → 本地设置”，关闭“不校验合法域名、TLS 版本及 HTTPS 证书”。
- [ ] 重新编译，使用体验版和真机验证登录、初始化、邀请和记账请求。

正式小程序不得使用 IP、HTTP、localhost、自签名证书或跳过域名校验。微信网络规则入口：[微信小程序网络通信与合法域名](https://developers.weixin.qq.com/miniprogram/dev/framework/ability/network.html)。

## 7. 配置中的关键设计说明

### 7.1 为什么 `proxy_pass` 后面没有 `/`

模板使用：

```nginx
proxy_pass http://127.0.0.1:7631;
```

不带尾部 URI，`/api/v1/entries` 会原样转发为 `/api/v1/entries`。如果误写成 `proxy_pass http://127.0.0.1:7631/;`，可能移除匹配前缀并导致后端 404。详细语义见：[Nginx proxy_pass 官方文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)。

### 7.2 当前登录路径使用新设计

当前设计基线使用：

```text
/api/v1/auth/wechat/login
/api/v1/auth/bootstrap
/api/v1/auth/invites/accept
```

旧文档中的 `/api/v1/auth/wechat-login` 已过期。模板已经按新路径设置较严格的认证接口限流。

Nginx IP 限流只是外围保护。Spring Boot 仍必须实现初始化口令一次性消费、邀请码原子消费、失败审计和权限校验；后端还应拒绝非规范的认证接口尾部斜杠，避免请求落入普通 API 限流规则。

### 7.3 不配置 CORS 和 WebSocket

- 微信原生小程序请求不使用浏览器 CORS 模型，不需要添加 `Access-Control-Allow-Origin: *`。
- 当前功能不使用 WebSocket，不需要复制 `Upgrade`/`Connection: upgrade` 配置。
- Nginx 默认转发 Authorization 请求头，但访问日志格式不记录 Authorization、查询参数或请求体。
- 邀请令牌、登录令牌和初始化口令必须放在请求体或 Authorization 头中，不要放进 URL 查询参数。

### 7.4 暂不启用 HSTS preload

首次上线不要立即启用 `includeSubDomains` 或 HSTS preload。先稳定运行 HTTPS并确认所有相关子域名都支持 HTTPS；确认无误后再单独评审 HSTS。

## 8. 常见问题定位

| 现象 | 优先检查 |
|---|---|
| `nginx -t` 报证书文件不存在 | 证书尚未签发就提前启用了 HTTPS 模板；恢复 HTTP 备份 |
| `502 Bad Gateway` | `systemctl status account-book`、`curl 127.0.0.1:7631`、7631 监听地址 |
| Nginx 404 | URL 是否以 `/api/v1/` 开头；后端路径是否与当前 API 设计一致 |
| DNS-01 签发失败 | DNS TXT 是否传播、插件是否匹配 DNS 服务商、API 凭据是否为最小权限、是否触发签发速率限制 |
| 本机正常但公网超时 | 京东云安全组或 UFW 没有同时放行，或 DNS 指向错误服务器 |
| 微信提示不在合法域名列表 | 公众平台未登记、填了路径/端口、仍在使用 IP 或 HTTP |
| 微信提示 TLS/证书错误 | 域名不匹配、证书过期、错误 AAAA、缺少完整证书链、TLS 版本过低 |
| 429 Too Many Requests | 命中认证/API 限流；检查是否存在重试循环，再按真实流量调整 |

排障命令：

```bash
sudo nginx -T
sudo journalctl -u nginx -n 200 --no-pager
sudo tail -n 200 /var/log/nginx/account-book.error.log
sudo ss -lntp
sudo ufw status numbered
sudo certbot certificates
dig +short A "$API_DOMAIN"
dig +short AAAA "$API_DOMAIN"
```

`nginx -T` 会输出完整生效配置。分享排障信息前检查其中是否包含内部域名或其他不应公开的信息。

## 9. 最终验收清单

- [ ] Nginx 使用 Ubuntu apt 安装并设置开机启动。
- [ ] `sudo nginx -t` 成功。
- [ ] Spring Boot 只监听 `127.0.0.1:7631`。
- [ ] MySQL 3306 不向公网开放。
- [ ] Nginx只代理 `/api/v1/`，未代理 `/actuator`。
- [ ] 登录、初始化和邀请接受接口使用当前 API 路径并有限流。
- [ ] MyTallyBook 只新增公网 443；既有 80 服务和规则保持不变；SSH 限制来源。
- [ ] DNS A 记录正确；不存在错误 AAAA。
- [ ] HTTPS 使用域名匹配的 `fullchain.pem`，TLS 验证返回 0。
- [ ] 已选定可自动续期的 DNS-01 插件或证书平台，并完成一次续期演练。
- [ ] 微信公众平台已登记 request 合法域名。
- [ ] 开发者工具已关闭跳过域名/TLS/证书校验。
- [ ] 公网无法访问 7631、8081 和 3306。
- [ ] Nginx、Spring Boot 和 MySQL 重启后均能恢复。

## 10. 官方参考

- [Ubuntu：安装 Nginx](https://ubuntu.com/server/docs/how-to/web-services/install-nginx/)
- [Ubuntu：配置 Nginx server block 与 HTTPS](https://ubuntu.com/server/docs/how-to/web-services/configure-nginx/)
- [Ubuntu：申请 TLS 证书](https://ubuntu.com/server/docs/how-to/security/obtain-tls-certificates/)
- [Ubuntu：UFW 防火墙](https://documentation.ubuntu.com/server/how-to/security/firewalls/index.html)
- [Nginx：反向代理模块](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)
- [Certbot：Nginx 安装与续期](https://certbot.eff.org/instructions?os=snap&ws=nginx)
- [Let's Encrypt：HTTP-01 与 DNS-01](https://letsencrypt.org/docs/challenge-types/)
- [京东云：安全组](https://docs.jdcloud.com/cn/virtual-machines/security-group-overview)
- [微信小程序：网络通信与合法域名](https://developers.weixin.qq.com/miniprogram/dev/framework/ability/network.html)
