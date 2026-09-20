# 随手账迁移到绿联 DXP4800 Pro：Docker 部署操作手册

> 更新：2026-09-20。适用：绿联 DXP4800 Pro、UGOS Pro、Docker Compose、当前 MyTallyBook 项目。
>
> 已知：已有域名，家庭公网 IPv4 和端口转发能力未知。本文提供两条完整联网路线。
>
> 本文是操作指南，不是已执行记录；本次编写没有连接 NAS 或云服务器，没有停服、迁库或改 DNS。

## 1. 先读这里：部署什么、按什么顺序做

NAS 上运行 **MySQL 数据库和 Java 后端**。微信小程序客户端仍使用微信开发者工具上传，在微信公众平台设置体验版、提审和发布，不作为网页上传到 NAS。

当前实际生产状态以 [上一份部署记录](26-备案完成后首次服务器迁移与小程序发布手册.md) 和现场检查为准：

| 项目 | 当前基线 |
|---|---|
| 旧服务器 | 京东云 `117.72.101.42`，SSH `root`、22 端口 |
| API 域名 | `https://www.cr-chenny.com` |
| 后端 | Java 21 / Spring Boot 4.1.0；`account-book.service` |
| 已发布运行包 | `/opt/account-book/current.jar`，指向实际 releases JAR |
| 正式数据库 | **`account_book_dev`，名字带 dev，但已经存放正式数据** |
| 旧 MySQL | `mysql8.service`，8.0.46；客户端 `/opt/mysql8/bin/` |
| 旧 MySQL socket | `/opt/mysql8/run/mysql.sock` |
| 数据结构 | 最近记录为 Flyway V1～V4、11 张表；以迁移当天查询为准 |
| 当前生产密钥 | `/etc/account-book/account-book.env`，以这里的有效值为准 |
| 现有证书 | `/etc/letsencrypt/live/www.cr-chenny.com/`；记录为手工 DNS-01 |
| 微信发布状态 | 已上传且体验版基本运行通过；正式发布需看微信后台 |

**特别注意密钥来源：** 上一轮云端首发时 Pepper 已由用户换新。本机 `secrets/local-dev/*.dpapi` 可能还是旧值，不能用它覆盖生产值。迁移沿用当前服务器的 `WECHAT_APP_ID`、`WECHAT_APP_SECRET`、`APP_BOOTSTRAP_KEY`、`APP_TOKEN_PEPPER`。目标 MySQL 是新实例，数据库密码可以新建；Pepper 不能随部署重新生成。

执行顺序：

1. 第 2～3 章：确认网络、安装 Docker、准备 NAS 目录。
2. 第 4～6 章：准备已发布 JAR、Docker 配置，只启动空的 MySQL。
3. 第 8 或第 9 章：提前准备对应网络路线，先不切 DNS 或正式代理。
4. 第 7 章：进入维护窗口，停旧后端 → 最终备份 → 导入 NAS → 核对 → 启动 NAS 后端。
5. 第 8 或第 9 章的“切换”：把正式入口指向 NAS；随后第 10～11 章验收微信。
6. 第 12～15 章：备份、恢复、更新、证书与排错。

先完成配置、镜像下载、证书和网络准备，再停旧服务，可缩短停机时间。每个代码块单独执行；失败后解决该步骤，不把整篇一次粘贴进终端。

## 2. 选择联网路线：有域名还需要可达的 HTTPS 入口

### 2.1 两条路线

| 条件 | 选择 | 公网请求路径 |
|---|---|---|
| 家庭公网 IPv4 可用，允许入站 TCP 443，能配置路由器转发 | A：第 8 章 | 微信 → 域名:443 → 路由器 → NAS Nginx → NAS 后端 → NAS MySQL |
| 无公网 IPv4、运营商封 443、不能控制上级路由，或暂不确定 | B：第 9 章 | 微信 → 京东云 Nginx:443 → SSH 加密隧道 → NAS 后端 → NAS MySQL |

**暂不确定时先用 B。** 它保留现有域名、证书和公网入口；云服务器只承担 Nginx/SSH 转发，数据库和业务计算迁到 NAS。代价是仍需保留云服务器，而且家中断网、NAS 停机时服务也会中断。

绿联远程访问入口方便管理 NAS，但不能直接当成项目的微信 request API 域名。只有 IPv6 也不按路线 A 的 IPv4 步骤上线，需要另行验证访问网络的 IPv6 覆盖。

### 2.2 怎样判断自己有没有公网 IPv4

1. 登录家中路由器，查看“上网状态 / WAN / Internet IPv4”。如果光猫负责拨号，也查看光猫 WAN 地址。
2. 在家中 Windows PowerShell 执行下面的查询，记录互联网看到的出口 IPv4；该地址仅用于比对：

```powershell
curl.exe -4 --fail --max-time 10 https://api.ipify.org
```

3. 对照路由器 WAN：

| WAN 地址 | 判断与下一步 |
|---|---|
| `10.*`、`192.168.*`、`172.16.*`～`172.31.*` | 私网；可能是光猫二级 NAT，也可能是运营商 NAT |
| `100.64.*`～`100.127.*` | 运营商共享地址段，通常不能直接做公网入站 |
| 与查询结果相同的公网地址 | 具备候选条件，**仍须验证外网 443 是否能进来** |
| WAN 与查询结果不同 | 存在上级 NAT、代理或多出口，继续核实，不直接判定可用 |

4. 如果只有光猫在上级 NAT，且光猫 WAN 是公网地址，可以在光猫和路由器两级做精确转发，或在了解拨号信息后调整桥接。新手优先用路线 B，避免改动家中拨号。
5. 最终以第 8 章部署后，手机关闭 Wi-Fi、使用移动数据能访问正常 HTTPS 为准。局域网内能打开、DDNS 显示正常，都不能替代外网验证。

域名现有网站备案、小程序备案和迁移后的接入信息应按平台/接入商当前要求核实；已有域名不等于已经具备全部发布条件。项目已有备案信息见第 11 章，不直接拿小程序备案号当网站备案号。

## 3. 绿联 NAS：安装 Docker、打开 SSH、准备目录

### 3.1 在 UGOS Pro 管理界面操作

1. 确认存储池和存储空间正常，在有数据的 NAS 上不要重新初始化硬盘。
2. 在路由器 DHCP 静态租约中固定 NAS 局域网地址，例如 `192.168.1.50`。
3. 在 UGOS Pro“应用中心”安装并启用 **Docker**。
4. 在控制面板中找到“终端 / SSH”相关设置，开启 SSH，记下端口；菜单名称随 UGOS 版本略有变化。只允许管理电脑所在局域网访问，不给 NAS 的 SSH/管理页面做公网转发。
5. 在文件管理器找到 Docker 共享目录，查看实际位置。下文用 `/volume1/docker/mytallybook` 举例；**必须替换为你设备真实存储路径**，不要凭示例创建错误的系统盘目录。

绿联 Docker 的“项目”基于 Compose，可导入 YAML；本文用 SSH 命令控制首次“仅启动数据库→导入→启动后端”的顺序。后续可在 Docker 界面查看容器、日志和资源状态；不要再用界面重复创建另一套同名项目。[绿联项目说明](https://support.ugnas.com/detail/article/en-US/411)

### 3.2 Windows 连接 NAS

以下在 **Windows PowerShell** 执行，替换账户、IP、SSH 端口：

```powershell
$NasUser = '替换为NAS管理员用户名'
$NasIp = '192.168.1.50'
$NasSshPort = 22
ssh -p $NasSshPort "$NasUser@$NasIp"
```

第一次连接核对主机指纹后输入 `yes`，再输入 NAS 用户密码。此后该窗口中的命令在 NAS 上运行。

### 3.3 NAS 只读检查

以下在 **NAS SSH** 执行：

```bash
uname -m
cat /etc/os-release
date -Is
df -h
free -h
sudo docker version
sudo docker compose version
sudo docker ps --format 'table {{.Names}}\t{{.Ports}}\t{{.Status}}'
sudo ss -lntp
command -v bash curl openssl ssh scp gzip sha256sum
```

预期 CPU 为 `x86_64`，Docker 服务正常，`docker compose` 为 v2 或后续兼容版本。若没有 Compose，先更新绿联 Docker 应用并按其文档启用；不直接在 UGOS 上执行 Ubuntu 的 Docker 安装脚本，不另装第二套 Docker。`curl`、`openssl`、`bash` 等缺失时需先补齐受支持工具，本文命令才能照用。

本项目建议预留约 2 GB 可用内存以及不少于 10 GB 空间，另按账目、日志和备份增长增加容量。下文 MySQL 限制 1 GB、后端 768 MB，为本项目低并发部署起始配置，应观察实际使用。

### 3.4 创建目录并固定命令入口

确认 `/volume1/docker` 是刚才查到的实际共享目录后执行：

```bash
# NAS SSH；如果当前不是 Bash，先执行 bash。
bash
NAS_DIR='/volume1/docker/mytallybook'
test -d /volume1/docker || { echo '请先改为真实Docker共享路径'; exit 1; }
sudo install -d -m 0700 -o "$(id -un)" -g "$(id -gn)" "$NAS_DIR"
cd "$NAS_DIR" || exit 1
mkdir -p app data/mysql backups incoming nginx cert secrets tunnel
chmod 700 backups incoming cert secrets tunnel
pwd -P
```

若 `mytallybook` 已有内容，先查清用途，不覆盖。以下简称 `dc` 为本项目 Compose：

```bash
NAS_DIR="$(pwd -P)"
dc() {
  sudo docker compose --project-directory "$NAS_DIR" \
    --env-file "$NAS_DIR/.env" -f "$NAS_DIR/compose.yaml" "$@"
}
```

**重新登录 NAS 后，先 `cd /你的真实目录/mytallybook`，再重新执行上面的 `NAS_DIR` 和 `dc()` 定义。** 不能在别的目录直接运行本文的相对路径操作。`sudo` 请求的是 NAS 管理密码。

## 4. 准备运行包和配置：首次搬迁沿用已经上线的版本

### 4.1 先在旧京东云检查当前版本

另开 **Windows PowerShell**，连接旧服务器：

```powershell
ssh -p 22 root@117.72.101.42
```

以下在 **旧服务器 Bash** 执行，均为只读：

```bash
systemctl is-active account-book.service mysql8.service nginx
readlink -f /opt/account-book/current.jar
sha256sum /opt/account-book/current.jar
curl -fsS --max-time 10 http://127.0.0.1:7631/actuator/health
/opt/mysql8/bin/mysql --version
```

记录实际 JAR 路径和 SHA-256。不要使用旧文档的固定摘要校验新版本。

### 4.2 在 NAS 下载当前云服务器运行包

以下在 **NAS SSH，项目目录** 执行。首次由 NAS 连接云服务器时，先在云控制台/既有连接确认 SSH 主机指纹，再接受；`scp` 提示输入的是云服务器密码。

```bash
scp -P 22 root@117.72.101.42:/opt/account-book/current.jar ./app/app.jar
sha256sum ./app/app.jar
chmod 644 ./app/app.jar
```

摘要必须与第 4.1 节一致。迁移准备期间不要再在云服务器发布另一版 JAR；若版本变了，重新记录和下载对应版本。这个方式不需要 NAS 安装 Maven，也不会覆盖 Windows 正在使用的 JAR。

### 4.3 填写 NAS 环境文件

1. 在自己的私密管理终端中打开旧服务器 `/etc/account-book/account-book.env`，取出**当前有效的四项微信/应用密钥**，保存在自己的密码管理器中。不要发到聊天、截图或 Git；不把该 systemd 文件直接当成 NAS Compose 文件。
2. 目标数据库 root 密码和应用密码分别新建为 32 字节随机值。例如在 NAS 运行两次 `openssl rand -hex 32`，分别记录；不要两者相同。
3. 在 NAS 项目目录创建以下模板，再用 `vi .env` 把所有 `__FILL_...__` 替换成真实值。

```bash
umask 077
test ! -e .env || { echo '.env已存在，请先核对，不能重新覆盖'; exit 1; }
cat > .env <<'EOF'
COMPOSE_PROJECT_NAME=mytallybook
MYSQL_IMAGE=mysql:8.0.46
APP_IMAGE=mytallybook-server:nas-20260920-1
NAS_LAN_IP=192.168.1.50
NAS_HTTPS_PORT=8443
MYSQL_ROOT_PASSWORD='__FILL_NEW_NAS_MYSQL_ROOT_PASSWORD__'
DB_PASSWORD='__FILL_NEW_NAS_APP_DATABASE_PASSWORD__'
WECHAT_APP_ID='wx9cb6fa756c2198d4'
WECHAT_APP_SECRET='__FILL_CURRENT_CLOUD_WECHAT_APP_SECRET__'
APP_BOOTSTRAP_KEY='__FILL_CURRENT_CLOUD_BOOTSTRAP_KEY__'
APP_TOKEN_PEPPER='__FILL_CURRENT_CLOUD_TOKEN_PEPPER__'
EOF
chmod 600 .env
vi .env
```

`vi` 基本操作：按 `i` 编辑；完成后按 `Esc`，输入 `:wq` 回车保存退出；放弃修改用 `:q!`。也可用你熟悉的安全编辑器；保持 UTF-8、LF 换行、无 BOM。

修改 `NAS_LAN_IP` 为 NAS 固定内网地址。`APP_IMAGE` 是这次发布的唯一标签，后续不要覆盖同名镜像。密码保留单引号；单引号中的 `$` 按字面保留，值本身含单引号时用 `\'` 转义。不要执行 `source .env`；它是 Compose 输入，不是 Shell 脚本。[Compose 环境文件规则](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)

检查没有遗漏占位符，只输出状态，不输出密钥内容：

```bash
if grep -q '__FILL_' .env; then
  echo '仍有未填写项，请继续编辑'
else
  echo '占位符已填写；仍需自行确认值来自当前生产配置'
fi
```

当前已初始化账本不需要再次初始化所有者。若旧服务器的 `APP_BOOTSTRAP_KEY` 本来为空，这里也写 `APP_BOOTSTRAP_KEY=''`；非空时沿用原值，长度为 20～256 字符。Pepper 至少 32 字符，必须保留当前有效值。使用错 Pepper 会使旧会话、未使用邀请的校验失败；若生产密钥无法取得，先解决密钥来源，再继续迁移。

## 5. 创建 Docker 文件

以下都是 **NAS SSH，项目目录**。文件只在首次不存在时创建；已有部署更新参见第 14 章。

### 5.1 Java 运行镜像

```bash
cat > app/Dockerfile <<'EOF'
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 accountbook \
    && useradd --uid 10001 --gid 10001 --no-create-home accountbook
WORKDIR /app
COPY --chown=10001:10001 app.jar /app/app.jar
USER 10001:10001
EXPOSE 7631
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
EOF

cat > app/.dockerignore <<'EOF'
*
!Dockerfile
!app.jar
EOF
```

Java 的更新标签首次构建会解析当时版本，后面记录镜像 ID，并归档已经验收的镜像。不要在迁移窗口同时升级 MySQL 大版本。本文选 `mysql:8.0.46` 是为了与当前源库一致，官方镜像该标签提供 amd64；如果现场源库版本有变化，先确定同版本迁移和后续升级计划，不改成 `latest`。[MySQL 镜像标签](https://hub.docker.com/_/mysql/tags?name=8.0&page=1)

### 5.2 Compose 主文件

```bash
cat > compose.yaml <<'EOF'
services:
  mysql:
    image: ${MYSQL_IMAGE:?fill MYSQL_IMAGE}
    restart: unless-stopped
    environment:
      TZ: Asia/Shanghai
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?fill root password}
      MYSQL_DATABASE: account_book_dev
      MYSQL_USER: account_book_dev_app
      MYSQL_PASSWORD: ${DB_PASSWORD:?fill database password}
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
      - --innodb-buffer-pool-size=256M
      - --max-connections=50
      - --event-scheduler=OFF
    volumes:
      - ./data/mysql:/var/lib/mysql
    networks:
      - database
    healthcheck:
      test: ["CMD-SHELL", "MYSQL_PWD=\"$$MYSQL_PASSWORD\" mysql --protocol=TCP -h 127.0.0.1 -u \"$$MYSQL_USER\" -D account_book_dev -Nse 'SELECT 1' >/dev/null"]
      interval: 10s
      timeout: 5s
      retries: 30
      start_period: 60s
    mem_limit: 1g
    logging:
      driver: json-file
      options:
        max-size: 10m
        max-file: "3"

  backend:
    image: ${APP_IMAGE:?fill APP_IMAGE}
    build:
      context: ./app
    restart: unless-stopped
    depends_on:
      mysql:
        condition: service_healthy
    environment:
      TZ: Asia/Shanghai
      SPRING_PROFILES_ACTIVE: prod
      SERVER_ADDRESS: 0.0.0.0
      SERVER_PORT: "7631"
      DB_URL: jdbc:mysql://mysql:3306/account_book_dev?connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&sslMode=DISABLED
      DB_USERNAME: account_book_dev_app
      DB_PASSWORD: ${DB_PASSWORD:?fill database password}
      WECHAT_APP_ID: ${WECHAT_APP_ID:?fill app id}
      WECHAT_APP_SECRET: ${WECHAT_APP_SECRET:?fill app secret}
      APP_BOOTSTRAP_KEY: ${APP_BOOTSTRAP_KEY-}
      APP_TOKEN_PEPPER: ${APP_TOKEN_PEPPER:?fill current production pepper}
      SPRING_FLYWAY_CLEAN_DISABLED: "true"
      SPRING_FLYWAY_BASELINE_ON_MIGRATE: "false"
      JAVA_TOOL_OPTIONS: -Xms128m -Xmx384m -Duser.timezone=Asia/Shanghai
    ports:
      - "127.0.0.1:7631:7631"
    networks:
      - database
      - edge
    read_only: true
    tmpfs:
      - /tmp:size=128m,mode=1777
    security_opt:
      - no-new-privileges:true
    stop_grace_period: 40s
    healthcheck:
      test: ["CMD", "curl", "--fail", "--silent", "--max-time", "5", "http://127.0.0.1:7631/actuator/health"]
      interval: 10s
      timeout: 6s
      retries: 30
      start_period: 60s
    mem_limit: 768m
    logging:
      driver: json-file
      options:
        max-size: 10m
        max-file: "3"

  nginx:
    image: nginx:stable-alpine
    profiles: ["https"]
    restart: unless-stopped
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "${NAS_LAN_IP:?fill NAS IP}:${NAS_HTTPS_PORT:-8443}:443"
    volumes:
      - ./nginx:/etc/nginx/conf.d:ro
      - ./cert:/etc/nginx/certs:ro
    networks:
      - edge
    logging:
      driver: json-file
      options:
        max-size: 10m
        max-file: "3"

networks:
  database:
    internal: true
  edge:
EOF
```

必须理解的对应关系：

| 配置 | 为什么这样写 |
|---|---|
| `SERVER_ADDRESS=0.0.0.0` | 容器内要接受 Docker 网络转发；项目 prod 默认回环监听需覆盖 |
| JDBC 主机 `mysql` | 它是 Compose 服务名；容器内 `127.0.0.1` 指自己 |
| 宿主机 `127.0.0.1:7631` | 只供 NAS 本机检查和路线 B 隧道访问，不公开后端 |
| MySQL 没有 `ports` | 不映射 NAS/公网 3306；只供内部数据库网络访问 |
| JDBC 禁用 TLS | 仅适用于本例同一 NAS 的隔离容器网络，不能复制到跨主机/公网数据库连接 |
| `nginx` 的 `https` profile | 只有路线 A 才启用；路线 B 由云 Nginx 终止 HTTPS |
| `depends_on` + healthcheck | 首次启动等待数据库实际就绪；不代表以后每次 unhealthy 都自动重启 |

Compose 的健康依赖控制初始启动顺序，容器进程异常退出由 restart 策略处理，健康故障仍需巡检。[Docker 启动顺序](https://docs.docker.com/compose/how-tos/startup-order/)

### 5.3 检查配置、下载、构建

```bash
dc config --quiet
dc pull mysql
dc build backend
sudo docker image ls --format 'table {{.Repository}}\t{{.Tag}}\t{{.ID}}'
```

`config --quiet` 无错误退出才继续。不要运行会展开密钥的 `docker compose config` 或把 `docker inspect` 的完整环境输出发到外部。镜像下载失败先修 Docker DNS/出站网络或用你信任的镜像仓库；不要随机换不明镜像。此时还没有停旧服务。

## 6. 只启动 NAS MySQL，等待导入

```bash
dc up -d mysql
dc ps
dc logs --tail=80 mysql
```

首次初始化通常需要一段时间，等 `mysql` 显示 `healthy`，再检查：

```bash
dc exec mysql mysql -uroot -p -D account_book_dev
```

输入**新 NAS MySQL root 密码**，进入 MySQL 后：

```sql
SELECT VERSION(), @@lower_case_table_names;
SHOW TABLES;
SHOW GRANTS FOR 'account_book_dev_app'@'%';
EXIT;
```

预期库是空库，应用账号仅获得 `account_book_dev.*` 的权限。官方镜像会创建能从其他容器连接的账号；旧的 `@127.0.0.1` 账号不能原样套用。**不要导入源服务器的 mysql 系统库、用户表或所有数据库。**

官方镜像的 `MYSQL_*` 初始化变量只对空数据目录生效；以后改 `.env` 不会自动修改数据库内部密码，账号变更需要对应 SQL 和服务配置一起调整。不要通过删除 `data/mysql` 来“重设密码”。[官方 MySQL 镜像初始化说明](https://hub.docker.com/_/mysql)

此时不要执行 `dc up -d` 或启动 backend；否则 Flyway 会先往空库建表，破坏预定的导入前提。先去准备路线 A/B 所需文件，再进入下一章维护窗口。

## 7. 最终停写、备份、搬迁数据库和启动 NAS 后端

### 7.1 进入维护窗口，停止旧写入

确认镜像和所选网络路线已经准备好，告知使用者短暂停用。关闭 Windows 上连接正式库的本地后端窗口；已有开发启动脚本仍可能连到云上正式 `account_book_dev`，迁移期间不要运行 `Start-Dev.cmd`。

在 **旧京东云服务器 Bash**：

```bash
systemctl is-enabled account-book.service
systemctl disable --now account-book.service
systemctl is-active account-book.service
```

先记录原启用状态（目前记录为 enabled），再禁用并停止，避免维护过程中云重启使旧后端重新写库。最后预期 `inactive`，状态查询返回非零属预期。保持 MySQL、Nginx、SSH 和原来占用 80 的 `/usr/weaver` 业务运行；不能为了迁移关闭整机或全部 Java。

用源库管理员连接，确认版本、历史、数据摘要和其他写入方：

```bash
/opt/mysql8/bin/mysql --protocol=SOCKET \
  --socket=/opt/mysql8/run/mysql.sock -uroot -p -D account_book_dev
```

```sql
SELECT VERSION(), @@lower_case_table_names;
SELECT installed_rank, version, script, checksum, success
FROM flyway_schema_history ORDER BY installed_rank;
SELECT 'app_config' AS t, COUNT(*) AS n FROM app_config
UNION ALL SELECT 'app_user', COUNT(*) FROM app_user
UNION ALL SELECT 'auth_session', COUNT(*) FROM auth_session
UNION ALL SELECT 'ledger', COUNT(*) FROM ledger
UNION ALL SELECT 'ledger_member', COUNT(*) FROM ledger_member
UNION ALL SELECT 'ledger_invite', COUNT(*) FROM ledger_invite
UNION ALL SELECT 'category', COUNT(*) FROM category
UNION ALL SELECT 'fund_account', COUNT(*) FROM fund_account
UNION ALL SELECT 'book_entry', COUNT(*) FROM book_entry
UNION ALL SELECT 'audit_log', COUNT(*) FROM audit_log;
SELECT COUNT(*) AS entries, COALESCE(SUM(amount),0) AS total_amount,
       MIN(id) AS first_id, MAX(id) AS last_id FROM book_entry;
SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES
WHERE TABLE_SCHEMA='account_book_dev' AND TABLE_TYPE='BASE TABLE';
SHOW FULL PROCESSLIST;
EXIT;
```

记录这些非秘密结果，用于导入后对比；金额字段实际为项目中的 `amount`。确认不存在其他应用/人工会话继续写该库，并且期间不执行 DDL。若源版本、表结构或 Flyway 与当前运行包不一致，先调查，不能跳过迁移检查。

### 7.2 源库最终备份

在 **旧服务器 Bash** 执行完整括号块，密码在 `-p` 提示中输入：

```bash
(
set -euo pipefail
umask 077
install -d -m 0700 /var/backups/account-book
MIGRATION_DIR=$(mktemp -d /var/backups/account-book/nas-final-XXXXXXXX)
/opt/mysql8/bin/mysqldump \
  --protocol=SOCKET --socket=/opt/mysql8/run/mysql.sock \
  --user=root -p --single-transaction --quick \
  --routines --triggers --events --hex-blob \
  --set-gtid-purged=OFF --no-tablespaces \
  --result-file="$MIGRATION_DIR/account_book_dev.sql" account_book_dev
test -s "$MIGRATION_DIR/account_book_dev.sql"
gzip "$MIGRATION_DIR/account_book_dev.sql"
cd "$MIGRATION_DIR"
gzip -t account_book_dev.sql.gz
sha256sum account_book_dev.sql.gz > SHA256SUMS
sha256sum -c SHA256SUMS
printf '记录迁移备份目录：%s\n' "$MIGRATION_DIR"
)
```

采用单库 dump，不使用 `--all-databases`，不带 `--databases`，保留全部业务表和 `flyway_schema_history`。一致性快照依赖事务表；快照期间不能同时改表。本项目现有表应为 InnoDB，非事务表或额外任务需先单独处理。[MySQL 备份参数说明](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html)

备份失败则停止搬迁；如果尚未切换且目标未产生新业务数据，可启动旧后端恢复服务，另约时间重做最终停写备份。不能拿几天前的备份代替这一份最终快照。

### 7.3 把备份复制到 NAS 并复核

在 **NAS SSH，项目目录**，把下方目录替换为上一步真正打印的路径：

```bash
SOURCE_BACKUP='/var/backups/account-book/nas-final-替换为实际编号'
IMPORT_DIR=$(mktemp -d "$NAS_DIR/incoming/nas-final-XXXXXXXX")
(
  set -euo pipefail
  test -d "$IMPORT_DIR"
  scp -P 22 "root@117.72.101.42:$SOURCE_BACKUP/account_book_dev.sql.gz" "$IMPORT_DIR/"
  scp -P 22 "root@117.72.101.42:$SOURCE_BACKUP/SHA256SUMS" "$IMPORT_DIR/"
  chmod 600 "$IMPORT_DIR/account_book_dev.sql.gz" "$IMPORT_DIR/SHA256SUMS"
  cd "$IMPORT_DIR"
  sha256sum -c SHA256SUMS
  gzip -t account_book_dev.sql.gz
  printf '本次导入目录：%s\n' "$IMPORT_DIR"
)
```

整个块成功并打印本次目录才继续，记录 `IMPORT_DIR`；每次使用全新目录，下载失败不会拿到旧备份。下节继续在同一 NAS 会话操作，重新登录时将 `IMPORT_DIR` 手工设为这个已验证的真实绝对路径。通过 scp 传文件，避免 Windows PowerShell 5.1 重定向二进制导致损坏。

### 7.4 导入空 NAS 库

把压缩包解压为受限文件，检查是否含库切换或自定义对象：

```bash
(
  set -e
  umask 077
  test -d "$IMPORT_DIR"
  gzip -dc "$IMPORT_DIR/account_book_dev.sql.gz" > "$IMPORT_DIR/account_book_dev.sql"
  test -s "$IMPORT_DIR/account_book_dev.sql"
)
grep -nE '^(CREATE DATABASE|USE )|DEFINER=|CREATE.*EVENT' "$IMPORT_DIR/account_book_dev.sql"
```

本项目标准迁移没有例程/事件。grep 没匹配会返回 1，属正常；若有固定库名、DEFINER 或事件，先检查是否会引用别的库，不盲目导入。目标保持 `event_scheduler=OFF`。

先再次确认 `SHOW TABLES` 为空；随后使用交互式密码登录导入，避免 stdin 同时被 SQL 和密码提示占用：

```bash
dc cp "$IMPORT_DIR/account_book_dev.sql" mysql:/tmp/nas-import.sql
dc exec mysql mysql --default-character-set=utf8mb4 -uroot -p -D account_book_dev
```

进入 MySQL 后：

```sql
SHOW TABLES;
SOURCE /tmp/nas-import.sql;
SHOW TABLES;
SELECT installed_rank, version, script, checksum, success
FROM flyway_schema_history ORDER BY installed_rank;
EXIT;
```

仔细确认导入输出没有任何 `ERROR`。客户端 `SOURCE` 可能在错误后继续，不能仅凭最后回到提示符判成功。有错误就保持 backend 停止，保存错误并调查，不把部分导入库再当空库重复导入。

重新执行第 7.1 节的行数、金额与 ID 摘要，与停写后源库结果逐项比较；包含软删除数据的总金额只是导入对照，不能当作小程序业务统计。还要核对表数、Flyway 成功状态/校验值、中文显示和关键账目。

核对一致后删除**容器中的临时明文**，保留受限迁移备份：

```bash
dc exec mysql rm -f /tmp/nas-import.sql
```

禁止为了让后端启动而执行 Flyway `clean`、`repair`、删历史表、`baseline`，也不要把 JPA 改成 `ddl-auto=update`。

### 7.5 启动后端，确认数据库连接和微信出站

```bash
dc up -d backend
dc ps
dc logs --tail=100 backend
```

第一次启动会执行 Flyway 校验和尚未执行的迁移。如果搬的是相同已上线 JAR，预期数据库结构无需变化；若出现新迁移，核查是否搬错包。出现错误先停在本步骤，不开放公网。

有界等待健康，在 **NAS Bash**：

```bash
(
set -e
for attempt in $(seq 1 60); do
  if curl -fsS --max-time 3 http://127.0.0.1:7631/actuator/health; then
    printf '\n后端健康检查成功\n'
    exit 0
  fi
  sleep 3
done
echo '后端未在等待窗口内就绪，检查日志和数据库'
exit 1
)
```

预期 HTTP 200、`{"status":"UP"}`。再检查匿名接口和出站网络：

```bash
curl -i --max-time 10 http://127.0.0.1:7631/api/v1/users/me
dc exec backend curl -I --connect-timeout 5 --max-time 10 https://api.weixin.qq.com
```

匿名接口预期 401，JSON 包含 `AUTHENTICATION_REQUIRED`。微信根站点可能返回非 200，它只帮助检查 DNS/TLS/出站连通性，真实 `wx.login` 登录仍需第 11 章验收。

此时旧后端保持停止。后面只选择路线 A 或路线 B 切换正式入口，避免两个可写服务各连一份独立库。

## 8. 路线 A：公网 IPv4 直达 NAS

只在公网 IPv4、入站 443 和路由器转发均可用时选择。8.1～8.3 在停写前准备；8.4～8.5 在第 7 章成功后执行。

### 8.1 上传和渲染现有 Nginx 模板

在 **Windows PowerShell，主目录**，填写真实 NAS 参数：

```powershell
Set-Location -LiteralPath 'D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project'
$NasUser = '替换为NAS管理员用户名'
$NasIp = '192.168.1.50'
$NasSshPort = 22
$NasDir = '/volume1/docker/mytallybook'
scp -P $NasSshPort .\deploy\nginx\account-book-https.conf.template .\deploy\nginx\account-book-proxy.inc "${NasUser}@${NasIp}:${NasDir}/incoming/"
if ($LASTEXITCODE -ne 0) { throw 'Nginx模板上传失败' }
```

在 **NAS SSH，项目目录** 渲染容器配置：

```bash
API_DOMAIN='www.cr-chenny.com'
sed -e 's/\r$//' \
  -e 's#http://127.0.0.1:7631;#http://backend:7631;#' \
  incoming/account-book-proxy.inc > nginx/account-book-proxy.inc
sed -e 's/\r$//' \
  -e 's#/etc/letsencrypt/live/__API_DOMAIN__/#/etc/nginx/certs/#g' \
  -e "s/__API_DOMAIN__/$API_DOMAIN/g" \
  -e 's#/etc/nginx/snippets/account-book-proxy.inc#/etc/nginx/conf.d/account-book-proxy.inc#g' \
  -e 's#/var/log/nginx/account-book.access.log#/dev/stdout#g' \
  -e 's#/var/log/nginx/account-book.error.log#/dev/stderr#g' \
  incoming/account-book-https.conf.template > nginx/account-book.conf
chmod 644 nginx/account-book.conf nginx/account-book-proxy.inc
```

保留原模板的登录/邀请限流、API 前缀和转发头覆盖。`proxy_pass http://backend:7631;` 没有结尾 `/`，保留 `/api/v1/...`；不要再把 NAS 管理代理叠在它前面。

### 8.2 导入现有受信任证书

沿用 `www.cr-chenny.com` 时，可从云服务器安全复制该域名证书。**NAS SSH，项目目录**：

```bash
umask 077
scp -P 22 root@117.72.101.42:/etc/letsencrypt/live/www.cr-chenny.com/fullchain.pem ./cert/fullchain.pem
scp -P 22 root@117.72.101.42:/etc/letsencrypt/live/www.cr-chenny.com/privkey.pem ./cert/privkey.pem
chmod 600 cert/privkey.pem
chmod 644 cert/fullchain.pem
openssl x509 -in cert/fullchain.pem -noout -dates -ext subjectAltName
openssl x509 -in cert/fullchain.pem -pubkey -noout | openssl sha256
openssl pkey -in cert/privkey.pem -pubout | openssl sha256
```

最后两个公钥摘要应相同。证书需覆盖实际 API 域名且未到期；私钥保持受限，Nginx 主进程可读取该挂载文件，不需要 `chmod 777`。

复制证书文件内容，不要只复制 `live` 下指向 `archive` 的符号链接。云端续期不会自动更新 NAS 副本，见第 15 章。

改用新域名时先通过 DNS-01 申请对应证书。DNS-01 不需开放 80，能在 DNS 切换前完成。[Let's Encrypt DNS-01](https://letsencrypt.org/docs/challenge-types/#dns-01-challenge)

### 8.3 检查端口、下载 Nginx

```bash
sudo ss -lntp | grep -E ':8443\b|:7631\b'
dc --profile https pull nginx
```

NAS `8443` 被占用时，在 `.env` 把 `NAS_HTTPS_PORT` 改为空闲高端口，例如 `18443`，下面内网检查和路由器目标端口一并修改。**公网仍用 443**，不停止 NAS 管理服务。

### 8.4 第 7 章通过后启动并检查

```bash
dc --profile https run --rm --no-deps nginx nginx -t
dc --profile https up -d nginx
dc --profile https ps
curl --resolve www.cr-chenny.com:8443:192.168.1.50 \
  --fail --show-error --max-time 10 \
  https://www.cr-chenny.com:8443/nginx-health
curl --resolve www.cr-chenny.com:8443:192.168.1.50 \
  -i --max-time 10 https://www.cr-chenny.com:8443/api/v1/users/me
```

替换实际 NAS IP/端口。HTTPS 预期 `nginx https ok`，匿名 API 预期应用 JSON 401。不使用 `-k` 绕过证书错误。

### 8.5 路由器和 DNS：切换入口

1. 路由器“端口转发 / 虚拟服务器”新增：协议 **TCP**，外部端口 **443**，内网主机 NAS IP（例 `192.168.1.50`），内部端口 **8443**。有两级 NAT 时两级都需配置；不启用整机 DMZ。
2. NAS 防火墙如启用，为所选 HTTPS 端口配置必要入站。Docker 端口是否受某条 UFW 规则约束以实际外网测试为准。
3. 路由器如用 WAN 443 管理，先通过路由器设置解决冲突。不要把 NAS 管理登录页当 API。
4. 在京东云 DNS 中把 `www` A 记录从 `117.72.101.42` 改为家庭公网 IPv4，保存原值/TTL 用于回退。没有正确 IPv6 链路就移除**本 API 主机**的无效 AAAA，不动其他域名记录。
5. 使用 NAS 支持的 DDNS 或服务商支持的更新方式，让 A 记录跟随动态 IPv4，并验证实际更新。手工改一次 A 记录不能保证下次拨号后可用；不能稳定维护时选 B。
6. 第 10 章外网检查成功后再恢复用户使用。

DNS 传播期间旧后端保持停止，旧 IP 缓存可能导致短暂 502。**不能重新启动连旧库的后端来兼容缓存。** 要平滑过渡，可事先按 B 建隧道，让旧云入口也指向同一 NAS 后端，再改 A 记录。

## 9. 路线 B：云 HTTPS 入口经 SSH 隧道连接 NAS

不需要家庭公网 IPv4 或路由器入站转发。NAS 主动连接云 SSH，云只在回环监听 `17631`；**公网不开放 17631、7631、3306**。

```text
www.cr-chenny.com:443（云 Nginx）
  → 云 127.0.0.1:17631（SSH -R）
  → 加密隧道
  → NAS 127.0.0.1:7631
  → backend 容器 → mysql 容器
```

9.1～9.4 可提前准备，不改旧入口；第 7 章成功后再执行 9.5。

### 9.1 NAS 生成专用隧道密钥

**NAS SSH，项目目录**：

```bash
umask 077
test ! -e tunnel/id_ed25519 || { echo '隧道密钥已存在，请先核对'; exit 1; }
ssh-keygen -t ed25519 -N '' -C mytallybook-nas-tunnel -f tunnel/id_ed25519
chmod 600 tunnel/id_ed25519
cat tunnel/id_ed25519.pub
```

复制显示的**公钥**整行。无口令私钥用于自动重连，保持目录 700、文件 600，并仅授权专用账号。

### 9.2 云服务器创建受限转发账号

**云 root Bash** 先查已有用途：

```bash
getent passwd nas-tunnel
ss -lntp | grep ':17631\b'
```

新环境两条应无输出（非零返回正常）。用户/端口已有用途时先调查。确认未占用再执行：

```bash
adduser --disabled-password --gecos '' nas-tunnel
install -d -m 0700 -o nas-tunnel -g nas-tunnel /home/nas-tunnel/.ssh
umask 077
cat > /home/nas-tunnel/.ssh/authorized_keys <<'EOF'
restrict,port-forwarding,permitlisten="127.0.0.1:17631",command="/bin/false" ssh-ed25519 替换为上一步公钥主体 mytallybook-nas-tunnel
EOF
chown nas-tunnel:nas-tunnel /home/nas-tunnel/.ssh/authorized_keys
chmod 600 /home/nas-tunnel/.ssh/authorized_keys
```

保留限制项，后面接完整 `ssh-ed25519 AAAA... 注释`，合为一行。强制命令阻止 shell 命令执行；隧道的 `ssh -N` 不申请 shell。

备份并编辑 SSH 配置，仅在没有同名段时于**主文件末尾**追加：

```bash
cp -p /etc/ssh/sshd_config "/etc/ssh/sshd_config.before-nas-$(date +%Y%m%d-%H%M%S)"
grep -n 'nas-tunnel' /etc/ssh/sshd_config
nano /etc/ssh/sshd_config
```

追加内容：

```text
Match User nas-tunnel
    AllowTcpForwarding remote
    AllowStreamLocalForwarding no
    PermitListen 127.0.0.1:17631
    GatewayPorts no
    PermitTTY no
    X11Forwarding no
    AllowAgentForwarding no
```

先验证，**保留当前 root SSH 窗口**以便恢复：

```bash
/usr/sbin/sshd -t
/usr/sbin/sshd -T -C user=nas-tunnel,host=nas,addr=192.0.2.1 \
  | grep -E 'allowtcpforwarding|allowstreamlocalforwarding|permitlisten|gatewayports|disableforwarding'
```

预期 `allowtcpforwarding remote`、`allowstreamlocalforwarding no`、`permitlisten 127.0.0.1:17631`、`gatewayports no`、`disableforwarding no`。已有 AllowUsers/Match/DisableForwarding 阻止访问时，按现有策略仅授权该用户所需能力。`addr` 是测试示例，有源 IP 条件时换实际 NAS 出口 IP。

验证无误才加载：

```bash
systemctl reload ssh
```

保留现有 root 登录方式，不设 `GatewayPorts yes`。[OpenSSH 转发限制](https://man.openbsd.org/sshd_config)

### 9.3 固定核实过的云主机指纹

**云服务器**查看：

```bash
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

**NAS SSH，项目目录**：

```bash
ssh-keyscan -p 22 -t ed25519 117.72.101.42 > tunnel/known_hosts.candidate
ssh-keygen -lf tunnel/known_hosts.candidate
```

两边 SHA256 指纹一致才继续：

```bash
mv tunnel/known_hosts.candidate tunnel/known_hosts
chmod 600 tunnel/known_hosts
```

`ssh-keyscan` 不自动证明身份；不使用 `StrictHostKeyChecking=no`。

### 9.4 Docker 保持隧道和自动重连

隧道使用 Linux host 网络以连接 NAS 回环端口，MySQL/后端仍按主 Compose 隔离。

**NAS SSH，项目目录**：

```bash
cat > tunnel/Dockerfile <<'EOF'
FROM debian:bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssh-client ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ENTRYPOINT ["ssh"]
EOF

cat > tunnel/.dockerignore <<'EOF'
*
!Dockerfile
EOF

cat > compose.tunnel.yaml <<'EOF'
services:
  tunnel:
    image: mytallybook-tunnel:20260920-1
    build:
      context: ./tunnel
    network_mode: host
    restart: unless-stopped
    read_only: true
    volumes:
      - ./tunnel/id_ed25519:/run/tunnel/id_ed25519:ro
      - ./tunnel/known_hosts:/run/tunnel/known_hosts:ro
    command:
      - -NT
      - -i
      - /run/tunnel/id_ed25519
      - -p
      - "22"
      - -o
      - BatchMode=yes
      - -o
      - IdentitiesOnly=yes
      - -o
      - StrictHostKeyChecking=yes
      - -o
      - UserKnownHostsFile=/run/tunnel/known_hosts
      - -o
      - ExitOnForwardFailure=yes
      - -o
      - ConnectTimeout=10
      - -o
      - ServerAliveInterval=30
      - -o
      - ServerAliveCountMax=3
      - -R
      - 127.0.0.1:17631:127.0.0.1:7631
      - nas-tunnel@117.72.101.42
    logging:
      driver: json-file
      options:
        max-size: 10m
        max-file: "3"
EOF

dt() {
  sudo docker compose --project-directory "$NAS_DIR" -p mytallybook-tunnel \
    -f "$NAS_DIR/compose.tunnel.yaml" "$@"
}
dt config --quiet
dt build
dt up -d
dt ps
dt logs --tail=50
```

新会话重新定义 `NAS_DIR`、`dt()`。隧道 `Up` 仅说明进程存在，必须结合云上请求检查。准备阶段后端未启动，连接下游失败属预期；不能因此提前启动空库后端。

**云服务器**检查：

```bash
ss -lntp | grep ':17631\b'
```

必须监听 `127.0.0.1:17631`，不是 `0.0.0.0` 或 `[::]`。不新增该端口安全组规则。DNS `www` A 记录保持 `117.72.101.42`。

### 9.5 第 7 章完成后切换云 Nginx 上游

**云服务器**先确认到达 NAS 后端：

```bash
curl -fsS --max-time 10 http://127.0.0.1:17631/actuator/health
curl -i --max-time 10 http://127.0.0.1:17631/api/v1/users/me
grep -n '^proxy_pass ' /etc/nginx/snippets/account-book-proxy.inc
```

前两项预期 200/UP、JSON 401。片段应为 `proxy_pass http://127.0.0.1:7631;`；若不同先调查实际链路。以下保存原片段并仅修改本项目，配置验证失败还原磁盘文件：

```bash
(
set -euo pipefail
ORIGINAL=/etc/nginx/snippets/account-book-proxy.inc
BACKUP="${ORIGINAL}.before-nas-$(date +%Y%m%d-%H%M%S)"
grep -qx 'proxy_pass http://127.0.0.1:7631;' "$ORIGINAL"
cp -p "$ORIGINAL" "$BACKUP"
sed 's#proxy_pass http://127.0.0.1:7631;#proxy_pass http://127.0.0.1:17631;#' \
  "$BACKUP" > "$ORIGINAL"
if nginx -t; then
  systemctl reload nginx
  printf '云入口已切到NAS；原片段备份：%s\n' "$BACKUP"
else
  cp -p "$BACKUP" "$ORIGINAL"
  echo '验证失败，已还原文件，未加载新配置'
  exit 1
fi
)
```

不改域名、证书、限流、转发头或云端 80。不运行旧 `deploy.ps1 -Apply`，也不把 Ubuntu 安装脚本用于 UGOS。云旧后端保持停止且禁用自启动。

## 10. 通用外网验收与完成切换

**Windows PowerShell**：

```powershell
Resolve-DnsName www.cr-chenny.com -Type A
Resolve-DnsName www.cr-chenny.com -Type AAAA -ErrorAction SilentlyContinue
curl.exe --fail --show-error --max-time 15 https://www.cr-chenny.com/nginx-health
curl.exe -i --max-time 15 https://www.cr-chenny.com/api/v1/users/me
curl.exe -i --max-time 15 https://www.cr-chenny.com/actuator/health
```

| 检查 | 正确结果 |
|---|---|
| A 记录 | A路线为家庭公网IPv4；B路线为117.72.101.42 |
| HTTPS `/nginx-health` | 200、`nginx https ok`，仅证明Nginx/TLS |
| 匿名 `/api/v1/users/me` | **401 + JSON `AUTHENTICATION_REQUIRED`**，证明到达认证入口 |
| 公网 `/actuator/health` | **404**，这是代理边界 |
| NAS内部 `/actuator/health` | 200、`UP` |

必须用**手机移动数据，关闭 Wi-Fi**打开域名并运行体验版；可让电脑连接手机热点复验 curl。内网、NAT 回流或模拟器成功不能代替外网验收。

NAS 用 `dc ps`、`dc logs --tail=80 backend`，B 路线加 `dt ps` 对照；旧云后端仍停止，目标数据正确，才能确认请求确实进入 NAS。不靠重新初始化探活。

第 11 章业务验收通过后，两种路线都在 **云服务器** 复核旧后端状态：

```bash
systemctl is-active account-book.service
systemctl is-enabled account-book.service
```

后两项预期 `inactive`、`disabled`，非零返回是正常状态表达。保留旧 JAR/配置/库和迁移备份，防止云重启后旧后端写旧库。**不停止整个 mysql8.service**，它可能有其他数据库。

## 11. 微信小程序发布与真机验收

### 11.1 是否需要重新上传

**继续使用 `https://www.cr-chenny.com`、接口未改时，仅迁移服务器通常无须重新上传小程序。** 已有体验版可直接验收 NAS，正式发布仍看微信后台状态。

当前 `account-book-miniapp/miniprogram/config/env.ts` 已配置：

```typescript
const DEPLOYED_API_ADDRESSES = {
  trialBaseUrl: 'https://www.cr-chenny.com',
  releaseBaseUrl: 'https://www.cr-chenny.com',
}
```

换域名时同步这两项、DNS、证书SAN、Nginx和微信合法域名，并重新上传。不能加 `/api/v1`、NAS IP、`:7631`、`:8443`。开发环境 develop 固定 `http://127.0.0.1:7631`，手机上的回环不是 NAS；应使用上传后的体验版 trial 测 NAS。`project.config.json` 的 `libVersion: "trial"` 不是环境地址开关。

### 11.2 后台与开发者工具步骤

1. 微信公众平台核对小程序 AppID `wx9cb6fa756c2198d4`。
2. 在“开发管理 / 开发设置 / 服务器域名”（以当前界面为准）核对 request 合法域名 `https://www.cr-chenny.com`。
3. 核对主体、小程序备案、隐私保护指引、服务类目和运营信息。当前展示备案号 `冀ICP备2026035005号-1X`、运营方 `Gitta`，联系方式在 `config/app-info.ts`；与后台实际资料保持一致。
4. 需要改客户端时，在 **Windows 主目录**验证：

```powershell
Set-Location -LiteralPath 'D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project'
node --version
npm.cmd --version
npm.cmd --prefix .\account-book-miniapp ci
if ($LASTEXITCODE -ne 0) { throw 'npm ci失败' }
npm.cmd --prefix .\account-book-miniapp test
if ($LASTEXITCODE -ne 0) { throw '小程序测试失败' }
npm.cmd --prefix .\account-book-miniapp run typecheck
if ($LASTEXITCODE -ne 0) { throw '类型检查失败' }
```

当前 Node 要求 `>=24.20.0 <25`，声明 npm `11.19.0`；项目没有 `npm run build/deploy`，由微信工具编译上传。

5. 开发者工具导入主目录 `account-book-miniapp`，核对 AppID，保留已有 `es6`、`enhance` 编译设置。
6. **取消勾选“不校验合法域名、TLS 版本以及 HTTPS 证书”**，即启用正常校验。
7. 编译后点“上传”，填写版本和说明，后台设为体验版、添加体验成员，扫码测试。
8. 体验/正式版目前共用一个 API/数据库，测试会影响这本账；用可辨识测试记录并按权限处理。
9. 未正式发布时，准备审核材料和可用的审核访问方式，提交审核，通过后发布，并验证实际正式版。上传成功、体验成功、正式发布是不同阶段。

微信官方入口：[网络能力说明](https://developers.weixin.qq.com/miniprogram/dev/framework/ability/network.html)。本次资料抓取未能读取此页面，具体后台规则以当前官方页面为准；本文地址格式已核对项目源码。

### 11.3 人工验收清单

| 用例 | 核对内容 |
|---|---|
| 会话与身份 | 原设备继续进入或正常重新登录，原成员和角色不变 |
| 历史数据 | 历史账目、中文分类、账户、人名、月份统计正确 |
| 新增记账 | 可辨识记录的金额/分类/账户/人名正确，其他成员能看到 |
| 修改和删除 | 有权成员操作正常，其他角色受到原权限限制 |
| 邀请 | 保留的有效旧邀请、新生成邀请按状态正常使用 |
| 资料与退出 | 昵称、退出、再次登录、会话恢复正常 |
| 网络/设备 | 家中Wi-Fi和移动网络；Mate 70 Pro、iPhone 15，记录系统/微信版本 |

导入账本不要再次初始化所有者。找不到账本先核对目标库、AppID、openid和成员关系。

## 12. NAS 备份：数据库、配置和运行包

### 12.1 创建并手工验证数据库备份脚本

**NAS SSH，项目目录**。脚本通过正在运行的容器导出单库，密码在容器内部取值，不拼进命令行参数；管道任一步失败都会返回失败。

```bash
cat > backup.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "$(readlink -f -- "$0")")"
TASK_DIR="$(pwd -P)"
exec 9>"$TASK_DIR/backups/.backup.lock"
flock -n 9 || { echo '已有备份任务运行'; exit 1; }
BACKUP_DIR=$(mktemp -d "$TASK_DIR/backups/daily-$(date +%Y%m%d-%H%M%S)-XXXXXX")
docker compose --project-directory "$TASK_DIR" --env-file "$TASK_DIR/.env" \
  -f "$TASK_DIR/compose.yaml" exec -T mysql sh -c '
  export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
  exec mysqldump -uroot --single-transaction --quick \
    --routines --triggers --events --hex-blob \
    --set-gtid-purged=OFF --no-tablespaces account_book_dev
' | gzip > "$BACKUP_DIR/account_book_dev.sql.gz.part"
gzip -t "$BACKUP_DIR/account_book_dev.sql.gz.part"
mv "$BACKUP_DIR/account_book_dev.sql.gz.part" "$BACKUP_DIR/account_book_dev.sql.gz"
cd "$BACKUP_DIR"
sha256sum account_book_dev.sql.gz > SHA256SUMS
sha256sum -c SHA256SUMS
printf '备份完成：%s\n' "$BACKUP_DIR"
EOF
chmod 700 backup.sh
bash -n backup.sh
command -v flock
sudo bash "$NAS_DIR/backup.sh"
```

gzip、摘要校验和成功路径正常后，按第 13 章恢复演练。`flock` 缺失先补齐，不定时运行一个手工未成功的脚本。脚本不自动删除历史；可按容量保留每日最近 7～14 份、每周至少 4 份，经验证后再清理明确的旧备份目录。

### 12.2 每天自动执行

当前 UGOS 若有支持管理员脚本的计划任务，在界面建每日任务，命令 `bash /实际目录/mytallybook/backup.sh`，以有 Docker 权限的 root 身份运行，不在非交互任务等待 sudo 密码。

无适用界面但 NAS 提供 cron 时，先检查时区/工具，再编辑 **root** 的 crontab：

```bash
date -Is
command -v crontab
command -v docker
sudo crontab -l
sudo crontab -e
```

保留原任务，追加一行。下面按 NAS 本地时间每天 03:15 执行；替换所有实际路径：

```cron
15 3 * * * PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash /volume1/docker/mytallybook/backup.sh >> /volume1/docker/mytallybook/backups/schedule.log 2>&1
```

Docker 在自定义目录时给 PATH 补上该目录，或在脚本中固定实际 Docker 绝对路径。保存后查看 `sudo crontab -l`，次日验证新备份文件和任务日志；UGOS 升级/重启后也检查。系统没有 cron/计划任务时先保留手工备份，不能称定时备份完成。

### 12.3 归档密钥、配置与镜像

**NAS SSH，项目目录**；镜像标签改成 `.env` 当前 `APP_IMAGE`：

```bash
(
set -euo pipefail
umask 077
ARCHIVE_DIR=$(mktemp -d "$NAS_DIR/backups/config-$(date +%Y%m%d-%H%M%S)-XXXXXX")
cp .env compose.yaml "$ARCHIVE_DIR/"
cp app/Dockerfile "$ARCHIVE_DIR/"
dc cp backend:/app/app.jar "$ARCHIVE_DIR/app.jar"
cp -a nginx cert "$ARCHIVE_DIR/"
if [ -f compose.tunnel.yaml ]; then
  cp compose.tunnel.yaml "$ARCHIVE_DIR/"
  cp -a tunnel "$ARCHIVE_DIR/"
fi
sudo docker image inspect mytallybook-server:nas-20260920-1 \
  --format '{{.Id}}' > "$ARCHIVE_DIR/backend-image-id.txt"
sudo docker save mytallybook-server:nas-20260920-1 | gzip > "$ARCHIVE_DIR/backend-image.tar.gz"
gzip -t "$ARCHIVE_DIR/backend-image.tar.gz"
(
  cd "$ARCHIVE_DIR"
  sha256sum app.jar backend-image.tar.gz > SHA256SUMS
  sha256sum -c SHA256SUMS
)
printf '受限配置归档：%s\n' "$ARCHIVE_DIR"
)
```

归档含 AppSecret、Pepper 和私钥，应与数据备份一起受限保存，不进 Git。NAS RAID、同一存储池快照/备份不能覆盖整机损坏。定期复制到另一设备或受保护异地存储并验证取回；不要以同步运行中的 MySQL data 文件夹替代逻辑备份。

## 13. 恢复演练与回退

### 13.1 独立临时容器恢复备份

在 **NAS SSH** 创建独立实例，不挂正式数据目录、不发布端口、不启动业务后端、不执行事件。源库若不是 8.0.46，镜像同步为经确认的备份兼容版本。

```bash
(
set -e
umask 077
DRILL_DIR=$(mktemp -d "$NAS_DIR/backups/restore-drill-XXXXXXXX")
openssl rand -hex 32 > "$DRILL_DIR/root-password"
printf 'MYSQL_ROOT_PASSWORD_FILE=/run/secrets/root-password\n' > "$DRILL_DIR/drill.env"
NAME="mytallybook-restore-$(date +%Y%m%d%H%M%S)"
sudo docker run -d --name "$NAME" --network none \
  --env-file "$DRILL_DIR/drill.env" \
  -v "$DRILL_DIR/root-password:/run/secrets/root-password:ro" \
  -v "$DRILL_DIR/data:/var/lib/mysql" \
  mysql:8.0.46 --event-scheduler=OFF
printf '记录容器名称：%s\n演练目录：%s\n' "$NAME" "$DRILL_DIR"
)
```

重新填写输出的名字和选定备份目录，检查日志直到 MySQL 就绪。由于第 12.1 节备份由 root 创建，校验/读取该目录使用 sudo：

```bash
DRILL_NAME='替换为mytallybook-restore-实际时间'
BACKUP_DIR='/volume1/docker/mytallybook/backups/替换为实际备份目录'
sudo docker logs --tail=60 "$DRILL_NAME"
sudo bash -c 'cd "$1" && sha256sum -c SHA256SUMS && gzip -t account_book_dev.sql.gz' _ "$BACKUP_DIR"
sudo docker exec "$DRILL_NAME" sh -c '
  export MYSQL_PWD="$(cat /run/secrets/root-password)"
  exec mysql -uroot -e "CREATE DATABASE account_book_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
'
```

按第 7.4 节检查备份无跨库/自定义事件等意外对象后，再完整执行导入块：

```bash
(
set -euo pipefail
sudo gzip -dc "$BACKUP_DIR/account_book_dev.sql.gz" \
  | sudo docker exec -i "$DRILL_NAME" sh -c '
    export MYSQL_PWD="$(cat /run/secrets/root-password)"
    exec mysql --default-character-set=utf8mb4 -uroot -D account_book_dev
  '
)
sudo docker exec "$DRILL_NAME" sh -c '
  export MYSQL_PWD="$(cat /run/secrets/root-password)"
  exec mysql -uroot -D account_book_dev -e "
    SHOW TABLES;
    SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank;
    SELECT COUNT(*), COALESCE(SUM(amount),0) FROM book_entry;
    SELECT COUNT(*) FROM app_user;
    SELECT COUNT(*) FROM ledger_member;"
'
```

比较备份时的记录，核对关键账目、分类、账户、初始化状态及成员关系，记录恢复耗时和结果。结束时可以只停容器保留演练数据：

```bash
sudo docker stop "$DRILL_NAME"
```

清理演练时重新核对容器和目录，不能选正式 mysql/data。正式恢复先在独立实例恢复验证，然后在停写窗口切到验证后的实例/数据目录，不将旧 SQL 直接覆盖正在使用的库。

### 13.2 NAS 尚未产生新数据时回退

前提：旧库在最终备份后未写入，NAS 也没有需要保留的新业务数据。否则走下一节。

1. NAS 执行 `dc stop backend`，阻止目标写入。
2. 云上原 JAR/env 本次未改则无须替换。恢复第 7.1 节记录的原状态；原为 enabled 时执行以下命令，原为 disabled 时仅 start、不 enable：

```bash
systemctl enable --now account-book.service
```

3. 等到云回环 `curl -fsS --max-time 10 http://127.0.0.1:7631/actuator/health` 返回 UP；尚未就绪检查日志，不把 start 返回当验收。
4. B 路线恢复第 9.5 节实际备份片段，检查后加载：

```bash
(
set -e
ORIGINAL_BACKUP='/etc/nginx/snippets/account-book-proxy.inc.before-nas-替换实际时间'
test -f "$ORIGINAL_BACKUP"
cp -p "$ORIGINAL_BACKUP" /etc/nginx/snippets/account-book-proxy.inc
nginx -t
systemctl reload nginx
)
```

5. A 路线将 `www` A 记录改回 `117.72.101.42`，等待缓存；若云也曾转发 NAS，恢复其原片段。
6. 重新做 HTTPS/API 和真实小程序验收。保留目标备份排查原因。

### 13.3 NAS 已产生新数据时回退

不能直接启用旧库，否则会丢迁移后的记录或造成数据分叉。

1. 停 NAS 后端，保留最新完整备份；两边都停写。
2. 用 NAS 最新数据在独立实例恢复并核对，确定兼容的 JAR/数据库版本。
3. 必须回云时，将最新数据迁回新的受控目标，验证后再切配置和入口。
4. 若决定恢复旧时间点，先明确之后的新账目如何补录，再开放服务。

镜像回滚不自动回滚 Flyway；新 JAR 启动失败前可能已执行 DDL，数据库不兼容时不能只换旧镜像。

## 14. 后续更新操作

### 14.1 Windows 主目录构建后端

仅用于以后有代码修改的发布。先确认本项目本地 Java 未占用 target 的运行包；有则在对应后端窗口 Ctrl+C 正常停止，不停全部 Java/原 SSH 隧道。

**Windows PowerShell**：

```powershell
Set-Location -LiteralPath 'D:\Work\Workplaces\privateWork\AAProject\MyTallyBook_Project'
git status --short
java -version
$env:DB_TEST_URL = ''
$env:DB_TEST_USERNAME = ''
$env:DB_TEST_PASSWORD = ''
$env:DB_TEST_RESET_ALLOWED = ''
Push-Location .\account-book-server
try {
  .\mvnw.cmd -o package
  if ($LASTEXITCODE -ne 0) { throw '后端构建失败，停止发布' }
} finally {
  Pop-Location
}
Get-FileHash .\account-book-server\target\account-book-server-0.0.1-SNAPSHOT.jar -Algorithm SHA256
```

离线依赖不全时确认电脑能联网，再去掉 `-o` 重试；不跳过测试。以上未启用真实数据库门禁，结构变更要在专用隔离实例演练，不能拿正式库测试。

上传到 NAS incoming，使用唯一文件名，不直接覆盖当前运行材料：

```powershell
$NasUser = '替换为NAS管理员用户名'
$NasIp = '192.168.1.50'
$NasDir = '/volume1/docker/mytallybook'
scp -P 22 .\account-book-server\target\account-book-server-0.0.1-SNAPSHOT.jar "${NasUser}@${NasIp}:${NasDir}/incoming/app-20261001-1.jar"
if ($LASTEXITCODE -ne 0) { throw '新包上传失败' }
```

### 14.2 NAS 更新

1. 记录旧标签/配置/Flyway，按第 12.3 节归档运行容器实际 JAR、env 和镜像。确认归档摘要通过。
2. 下方填写 Windows 计算的新包 SHA-256（64位十六进制）。完整块任一步失败即停；停止后端后若失败，保持停写，按归档判断恢复，不继续粘贴后续步骤。编辑 `.env` 时只把 `APP_IMAGE` 改成下方新标签，其余密钥不变。

```bash
(
set -euo pipefail
EXPECTED_SHA='替换为Windows计算的64位SHA256'
NEW_IMAGE='mytallybook-server:nas-20261001-1'
[[ "$EXPECTED_SHA" =~ ^[0-9a-fA-F]{64}$ ]]
printf '%s  %s\n' "$EXPECTED_SHA" incoming/app-20261001-1.jar | sha256sum -c -
if sudo docker image inspect "$NEW_IMAGE" >/dev/null 2>&1; then
  echo '新标签已存在，请使用另一个唯一标签'
  exit 1
fi
dc stop backend
sudo bash "$NAS_DIR/backup.sh"
cp incoming/app-20261001-1.jar app/app.jar
chmod 644 app/app.jar
vi .env
grep -qx "APP_IMAGE=$NEW_IMAGE" .env
dc config --quiet
dc build backend
dc up -d --no-deps --no-build backend
)
```

3. 第 7.5 节健康等待成功后，A 路线复核并重启 Nginx，以重新解析重建后可能变化的 backend 容器 IP：

```bash
dc --profile https exec nginx nginx -t && dc --profile https restart nginx
```

B 路线不运行 NAS Nginx 命令；隧道目标为 NAS 固定回环端口，检查云 `127.0.0.1:17631` 和公网。

4. 验证旧客户端，再上传新小程序，体验→提审→发布。微信更新与服务器不同步，保留必要接口兼容。

仅改 `.env` 时，使用 `dc up -d --no-deps --force-recreate backend` 让新容器读取配置；`restart` 不重新读取 Compose 的环境值。A 路线随后重启 Nginx。更换 MySQL 密码还需实际账号 SQL，不能只改 env。

### 14.3 仅回滚镜像

前提是旧 JAR 与当前数据库兼容。填写第 12.3 节旧版本归档目录和旧标签；同时恢复对应的构建输入 JAR，使下次归档/构建不把新包误当旧版本。以下不恢复数据库，不重新 build：

```bash
(
set -euo pipefail
OLD_ARCHIVE='/volume1/docker/mytallybook/backups/config-替换为旧归档目录'
OLD_IMAGE='mytallybook-server:nas-20260920-1'
test -d "$OLD_ARCHIVE"
(cd "$OLD_ARCHIVE" && sha256sum -c SHA256SUMS)
test -s "$OLD_ARCHIVE/backend-image-id.txt"
ACTUAL_IMAGE_ID=$(sudo docker image inspect "$OLD_IMAGE" --format '{{.Id}}')
EXPECTED_IMAGE_ID=$(cat "$OLD_ARCHIVE/backend-image-id.txt")
test "$ACTUAL_IMAGE_ID" = "$EXPECTED_IMAGE_ID"
dc stop backend
cp "$OLD_ARCHIVE/app.jar" app/app.jar
chmod 644 app/app.jar
vi .env
grep -qx "APP_IMAGE=$OLD_IMAGE" .env
dc config --quiet
dc up -d --no-deps --no-build backend
)
```

编辑时将 `APP_IMAGE` 改为旧标签；若旧镜像已不在 NAS，先在验证归档后用 `gzip -dc "$OLD_ARCHIVE/backend-image.tar.gz" | sudo docker load`（在 `set -euo pipefail` 块内）恢复，再执行回滚块。等待 UP，A 路线重启 Nginx，复验公网和业务。重新 build 会破坏旧标签的回滚材料；数据库回退另按第 13 章处理。

## 15. 运维、证书续期与排错

### 15.1 日常检查和重启验证

**NAS SSH，项目目录，已定义 dc**：

```bash
dc ps
dc logs --tail=100 backend
dc logs --tail=100 mysql
sudo docker stats --no-stream
df -h
curl -fsS --max-time 5 http://127.0.0.1:7631/actuator/health
```

A 路线加 `dc --profile https ps`、`dc --profile https logs --tail=80 nginx`；B 路线加 `dt ps`、`dt logs --tail=80`。不打印完整容器环境，含个人信息的日志留在管理环境内排查。

维护窗口做一次 NAS 重启测试，确认 UGOS Docker 自启、容器、B 路线隧道、内部 UP 和外网业务恢复。`unless-stopped` 不会替你恢复手工停止的容器，需要对应 `up -d`。NAS 定时关机、宽带断网和路由器重启都会影响服务。

普通后端重启用 `dc restart backend`，A 路线复核代理。MySQL 恢复慢时等待和查日志，不连续删容器/数据目录。

### 15.2 证书续期

旧记录到期日 **2026-12-19** 仅供参考，每次看实际证书。A 路线在 NAS：

```bash
openssl x509 -in cert/fullchain.pem -noout -dates
openssl x509 -in cert/fullchain.pem -noout -checkend 2592000
```

第二条非零表示将在 30 天内过期或已经无效；B 路线检查云 `/etc/letsencrypt/live/www.cr-chenny.com/fullchain.pem`。

当前手工 DNS-01 不会因为安装了 Certbot timer 就自动续期。保留云时，在 **云 root Bash** 申请/续期：

```bash
certbot certonly --manual --preferred-challenges dns \
  --cert-name www.cr-chenny.com -d www.cr-chenny.com
```

按当前挑战提示在京东云 DNS 设置 `_acme-challenge.www` TXT，保留等待窗口。另开 **Windows PowerShell**：

```powershell
Resolve-DnsName _acme-challenge.www.cr-chenny.com -Type TXT -Server 223.5.5.5
Resolve-DnsName _acme-challenge.www.cr-chenny.com -Type TXT -Server 119.29.29.29
```

确认等于本次新值、传播完成再回车，不复用历史 TXT。详见 [原部署手册](26-备案完成后首次服务器迁移与小程序发布手册.md)。手工认证无自动 hook 时须人工配合。[Certbot manual 说明](https://eff-certbot.readthedocs.io/en/stable/using.html#manual)

A 路线在 **NAS** 将新证书先下载到 incoming：

```bash
umask 077
scp -P 22 root@117.72.101.42:/etc/letsencrypt/live/www.cr-chenny.com/fullchain.pem ./incoming/fullchain.pem
scp -P 22 root@117.72.101.42:/etc/letsencrypt/live/www.cr-chenny.com/privkey.pem ./incoming/privkey.pem
```

按第 8.2 节方法检查 incoming 文件的 SAN/期限/公钥对应，受限备份旧证书后：

```bash
install -m 0644 incoming/fullchain.pem cert/fullchain.pem
install -m 0600 incoming/privkey.pem cert/privkey.pem
dc --profile https exec nginx nginx -t
```

上一步成功才加载：

```bash
dc --profile https exec nginx nginx -s reload
```

B 路线在云上 `nginx -t` 通过后 `systemctl reload nginx`。两条路线都从外网验证实际送出的新证书，不只看磁盘文件。

A 路线若计划停用云服务器，先将签发/续期工具与记录迁到 NAS 或另一受控管理机，选择 DNS 服务商支持的自动插件或继续人工 DNS-01。替代流程验证前不删除原签发材料；自动续期尚未实施时明确登记人工负责人和到期日。

### 15.3 常见故障

| 现象 | 排查顺序 |
|---|---|
| `dc`不存在 | 进入真实项目目录，重新定义第3.4节函数 |
| Compose不存在 | 检查/更新UGOS Docker，不盲装第二套Engine |
| 镜像下载失败 | NAS DNS、出站网络、服务可用性、可信镜像源 |
| MySQL Access denied | NAS账号密码、env与内部账号一致性；不能照搬旧host授权 |
| Public Key Retrieval错误 | 核对本例JDBC参数，只用于隔离同机网络 |
| backend连不上数据库 | mysql健康、服务DNS、database网络、库名和密码 |
| 502 | 后端健康/日志；A检查DNS并重启Nginx；B检查隧道/云17631 |
| Flyway checksum/缺表 | JAR、完整dump、历史表，不clean/repair掩盖 |
| 家里可用外网失败 | 公网IP、上级NAT、运营商端口；必要时改B |
| 出现NAS管理页 | 路由器应443→本项目高端口，不是NAS管理HTTPS |
| TLS失败 | SAN、期限、完整链、实际证书；不-k/不关闭微信校验 |
| 隧道Permission denied | 公钥整行、目录权限、sshd Match/AllowUsers、已固定主机指纹 |
| forwarding failed | 云17631占用、PermitListen/GatewayPorts；不开放公网端口解决 |
| 微信登录失败 | AppID/AppSecret配对、NAS出站微信443、时钟和应用日志 |
| 会话/邀请全失效 | 是否误用本地旧Pepper或连错数据库 |
| 手机开发预览失败 | develop用127.0.0.1，应使用上传后的体验版 |
| 重启未恢复 | Docker自启、容器是否手工stop、隧道和挂载数据盘 |
| 备份未定时生成 | 手工脚本、任务用户、Docker路径、日志和NAS时区 |

### 15.4 留下本次记录

登记日期、执行人、NAS真实路径/IP、A/B路线、JAR摘要/镜像标签、MySQL版本、Flyway校验、最终备份路径/摘要、数据对照、入口切换、真机结果、回滚材料、恢复演练、证书期限和实际定时备份触发。

现有 `Start-Dev.cmd` 仍是旧云数据库启动逻辑，不能靠临时改环境变量完成开发隔离。后续另建独立开发/测试库和配置，不用 NAS 正式库跑清库测试；开发/构建/本地启动继续只在主目录，不用历史 worktree。

## 16. 本文验证边界

本文按主目录的 `application*.yml`、`pom.xml`、Flyway V1～V4、Nginx模板、小程序配置和最新部署记录编写。Compose、Dockerfile、脚本均是文中提供的待部署内容，命令须在各节标明的 Windows/NAS/云服务器执行。

本轮本地检查：58 段 Bash 及嵌入的备份脚本语法通过；9 段 PowerShell 解析通过；两份 Compose YAML 解析及端口/网络/健康依赖静态核对通过；使用仓库真实模板运行 Nginx 渲染命令，验证容器上游、证书路径、日志路径和路由保留正确；本地文档链接有效。数据库/网络流程另经独立只读复核。这些不是容器运行或 NAS 实测结果。

编写机器没有可用 Docker Engine，未实际运行容器，也未验证你的 NAS 路径、家庭公网入站、云SSH策略或微信后台。语法/文本检查不能代替目标环境 `dc config --quiet`、`nginx -t`、数据库对照、恢复演练与真机验收；现场通过后再记录迁移完成。
