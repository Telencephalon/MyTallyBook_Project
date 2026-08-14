# 京东云 Ubuntu 卸载并重新安装 MySQL 8.4 LTS 执行手册

> 文档版本：V1.0
>
> 编制日期：2026-08-14
>
> 适用系统：Ubuntu、Debian 系列 Linux
>
> 目标：使用 Oracle MySQL APT 仓库全新安装 MySQL 8.4 LTS，并在初始化阶段设置 `lower_case_table_names=1`

## 1. 执行结论

单独卸载 MySQL 软件包可执行：

```bash
sudo apt-get remove --purge -y mysql-server mysql-client mysql-common
```

但是，上述单条命令不能完成以下工作：

- 不保证完整备份数据库。
- 不保证清理或隔离原数据目录。
- 不保证重新初始化 `lower_case_table_names=1`。
- 不保证安装后加载本项目需要的全部参数。

因此不要只执行这一条命令。需要全新安装时，应严格执行本文后续完整流程。

## 2. 最终目标参数

本次重新安装后的配置为：

```ini
[mysqld]
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
innodb_buffer_pool_size=512M
log_bin_trust_function_creators=1
lower_case_table_names=1
max_connections=5000
sql_mode=NO_ENGINE_SUBSTITUTION,STRICT_TRANS_TABLES
transaction_isolation=READ-COMMITTED
group_concat_max_len=102400
```

注意：

- 本文使用 `utf8mb4`，不是已经弃用的 `utf8`/`utf8mb3` 别名。
- `lower_case_table_names=1` 必须在数据目录第一次初始化前确定。
- `max_connections=5000` 按指定值配置。对于 10 人以内的应用，实际建议使用 `300`，Spring Boot Hikari 连接池最大值建议为 `10`。

## 3. 数据安全策略

本文不直接删除 `/var/lib/mysql`，而是将其移动到带时间戳的备份目录：

```text
/var/backups/mysql-reinstall-年月日_时分秒/
```

该目录可能包含：

```text
full-backup.sql.gz       # 全库逻辑备份
datadir/                 # 原 /var/lib/mysql
etc-mysql-before-purge/  # 原配置备份
installed-packages.txt   # 原安装包清单
```

在新实例完成验证、应用完成联调且备份保留期结束前，不要删除该目录。

## 4. 第一步：进入 root shell 并检查操作系统

```bash
sudo -i
```

确认系统：

```bash
set -u

. /etc/os-release
printf 'ID=%s\nVERSION_ID=%s\nPRETTY_NAME=%s\n' \
  "${ID:-unknown}" "${VERSION_ID:-unknown}" "${PRETTY_NAME:-unknown}"

case "${ID:-}" in
  ubuntu|debian)
    echo "操作系统检查通过"
    ;;
  *)
    echo "ERROR: 本文只适用于 Ubuntu/Debian，当前系统是 ${ID:-unknown}"
    exit 1
    ;;
esac
```

如果此处退出，不要继续执行。Rocky Linux、AlmaLinux、CentOS Stream 使用的是 RPM/DNF 流程，卸载命令不同。

## 5. 第二步：建立本次操作变量

以下变量在后续所有命令中使用。执行过程中不要关闭当前 SSH/root shell：

```bash
MYSQL_REINSTALL_STAMP="$(date +%Y%m%d_%H%M%S)"
MYSQL_REINSTALL_BACKUP="/var/backups/mysql-reinstall-${MYSQL_REINSTALL_STAMP}"

install -d -m 700 "${MYSQL_REINSTALL_BACKUP}"

echo "MYSQL_REINSTALL_STAMP=${MYSQL_REINSTALL_STAMP}"
echo "MYSQL_REINSTALL_BACKUP=${MYSQL_REINSTALL_BACKUP}"
```

验证备份目录必须位于 `/var/backups/` 下：

```bash
MYSQL_REINSTALL_BACKUP_REAL="$(readlink -f -- "${MYSQL_REINSTALL_BACKUP}")"

case "${MYSQL_REINSTALL_BACKUP_REAL}" in
  /var/backups/mysql-reinstall-*)
    echo "备份路径检查通过：${MYSQL_REINSTALL_BACKUP_REAL}"
    ;;
  *)
    echo "ERROR: 备份路径异常，停止执行：${MYSQL_REINSTALL_BACKUP_REAL}"
    exit 1
    ;;
esac
```

## 6. 第三步：安装前只读检查

### 6.1 查看 MySQL 服务

```bash
systemctl status mysql --no-pager || true
systemctl status mysqld --no-pager || true
```

### 6.2 查看版本和数据目录

本文默认 root 可以通过本地 Socket 登录：

```bash
mysql --protocol=socket --table -e "
SELECT VERSION() AS mysql_version,
       @@version_comment AS version_comment,
       @@hostname AS hostname,
       @@datadir AS data_directory,
       @@GLOBAL.lower_case_table_names AS lower_case_table_names;
"
```

如果提示 `Access denied`，后续所有 `mysql --protocol=socket` 和 `mysqldump --protocol=socket` 命令分别改为：

```bash
mysql -uroot -p
mysqldump -uroot -p
```

不要把 root 密码直接写在 `-p` 后面。

### 6.3 保存已安装的软件包清单

```bash
dpkg-query -W -f='${binary:Package}\t${Version}\n' 2>/dev/null \
  | grep -E '^(mysql|libmysql)' \
  | sort \
  | tee "${MYSQL_REINSTALL_BACKUP}/installed-packages.txt" \
  || true
```

### 6.4 检查是否混装 MariaDB

```bash
if dpkg-query -W -f='${binary:Package}\n' 2>/dev/null \
  | grep -Eq '^mariadb-(server|client)'; then
  echo "ERROR: 检测到 MariaDB。不要执行本文，需先确认数据库发行版和迁移方式。"
  exit 1
fi
```

### 6.5 检查备份空间

```bash
du -sh /var/lib/mysql 2>/dev/null || true
df -h /var/lib/mysql /var/backups
```

备份目录必须同时容纳逻辑备份和原数据目录。如果 `/var/backups` 与 `/var/lib/mysql` 不在同一文件系统，移动数据目录实际会发生复制，必须预留足够空间并等待复制完成。

## 7. 第四步：备份数据库和配置

### 7.1 验证当前数据目录

自动流程只允许处理 MySQL 默认目录 `/var/lib/mysql`：

```bash
MYSQL_CURRENT_DATADIR="$(
  mysql --protocol=socket --batch --skip-column-names \
    -e "SELECT @@datadir;" \
    | tr -d '[:space:]'
)"

MYSQL_CURRENT_DATADIR_REAL="$(readlink -f -- "${MYSQL_CURRENT_DATADIR%/}")"

echo "MYSQL_CURRENT_DATADIR=${MYSQL_CURRENT_DATADIR}"
echo "MYSQL_CURRENT_DATADIR_REAL=${MYSQL_CURRENT_DATADIR_REAL}"

if [ "${MYSQL_CURRENT_DATADIR_REAL}" != "/var/lib/mysql" ]; then
  echo "ERROR: 当前数据目录不是 /var/lib/mysql，停止自动卸载。"
  exit 1
fi
```

### 7.2 创建全库逻辑备份

```bash
set -o pipefail

if ! mysqldump --protocol=socket \
  --all-databases \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  --hex-blob \
  --set-gtid-purged=OFF \
  | gzip > "${MYSQL_REINSTALL_BACKUP}/full-backup.sql.gz"; then
  echo "ERROR: mysqldump 失败，禁止继续卸载。"
  exit 1
fi

gzip -t "${MYSQL_REINSTALL_BACKUP}/full-backup.sql.gz" || exit 1
sha256sum "${MYSQL_REINSTALL_BACKUP}/full-backup.sql.gz" \
  | tee "${MYSQL_REINSTALL_BACKUP}/full-backup.sql.gz.sha256"
```

说明：全库备份包含系统库，只用于灾难恢复。恢复业务时应优先只恢复业务数据库，不要把旧版本的 `mysql` 系统库直接覆盖到新实例。

### 7.3 备份 MySQL 配置

```bash
if [ -d /etc/mysql ]; then
  cp -a /etc/mysql "${MYSQL_REINSTALL_BACKUP}/etc-mysql-before-purge"
fi

if [ -f /etc/my.cnf ]; then
  cp -a /etc/my.cnf "${MYSQL_REINSTALL_BACKUP}/my.cnf.before-purge"
fi

find "${MYSQL_REINSTALL_BACKUP}" -maxdepth 2 -mindepth 1 -ls
```

## 8. 第五步：人工确认

只有在以下条件全部满足后才能继续：

- 已确认这是要重装的 MySQL 服务器。
- `gzip -t` 没有报错。
- 已记录 `MYSQL_REINSTALL_BACKUP` 路径。
- 已确认应用当前可以停机。

输入指定确认文本：

```bash
echo "即将停止 MySQL、卸载软件包并移动原数据目录。"
read -r -p "输入 REINSTALL MYSQL 继续：" MYSQL_REINSTALL_CONFIRM

if [ "${MYSQL_REINSTALL_CONFIRM}" != "REINSTALL MYSQL" ]; then
  echo "未确认，操作终止。"
  exit 1
fi
```

## 9. 第六步：停止服务并移动原数据目录

### 9.1 停止服务

```bash
systemctl stop mysql 2>/dev/null || true
systemctl stop mysqld 2>/dev/null || true

if systemctl is-active --quiet mysql || systemctl is-active --quiet mysqld; then
  echo "ERROR: MySQL 仍在运行，禁止移动数据目录。"
  exit 1
fi
```

### 9.2 再次验证并移动数据目录

```bash
if [ -e /var/lib/mysql ]; then
  MYSQL_DATA_REAL="$(readlink -f -- /var/lib/mysql)"

  if [ "${MYSQL_DATA_REAL}" != "/var/lib/mysql" ]; then
    echo "ERROR: /var/lib/mysql 解析为 ${MYSQL_DATA_REAL}，停止执行。"
    exit 1
  fi

  mv -- /var/lib/mysql "${MYSQL_REINSTALL_BACKUP}/datadir"
  echo "原数据目录已移动到 ${MYSQL_REINSTALL_BACKUP}/datadir"
fi
```

可选目录同样采用移动备份，不直接删除：

```bash
if [ -e /var/lib/mysql-files ]; then
  MYSQL_FILES_REAL="$(readlink -f -- /var/lib/mysql-files)"
  if [ "${MYSQL_FILES_REAL}" != "/var/lib/mysql-files" ]; then
    echo "ERROR: mysql-files 路径异常：${MYSQL_FILES_REAL}"
    exit 1
  fi
  mv -- /var/lib/mysql-files "${MYSQL_REINSTALL_BACKUP}/mysql-files"
fi

if [ -e /var/lib/mysql-keyring ]; then
  MYSQL_KEYRING_REAL="$(readlink -f -- /var/lib/mysql-keyring)"
  if [ "${MYSQL_KEYRING_REAL}" != "/var/lib/mysql-keyring" ]; then
    echo "ERROR: mysql-keyring 路径异常：${MYSQL_KEYRING_REAL}"
    exit 1
  fi
  mv -- /var/lib/mysql-keyring "${MYSQL_REINSTALL_BACKUP}/mysql-keyring"
fi
```

## 10. 第七步：卸载 MySQL 软件包

自动获取已安装的 MySQL 服务端、客户端和公共配置包：

```bash
mapfile -t MYSQL_REMOVE_PACKAGES < <(
  dpkg-query -W -f='${binary:Package}\n' 2>/dev/null \
  | grep -E '^mysql-(server($|-)|client($|-)|common($|:)|community-(server|client|client-core|client-plugins|common)($|:))' \
  | sort -u
)

printf '准备卸载的软件包：\n'
printf '  %s\n' "${MYSQL_REMOVE_PACKAGES[@]}"
```

执行卸载：

```bash
if [ "${#MYSQL_REMOVE_PACKAGES[@]}" -gt 0 ]; then
  apt-get purge -y "${MYSQL_REMOVE_PACKAGES[@]}"
else
  echo "没有检测到可卸载的 MySQL 主软件包"
fi
```

本文不自动执行 `apt autoremove`，防止删除服务器上其他被标记为“自动安装”的软件。

查看卸载后的残留包：

```bash
dpkg-query -W -f='${binary:Package}\t${Version}\n' 2>/dev/null \
  | grep -E '^(mysql|libmysql)' \
  | sort \
  || true
```

### 10.1 隔离卸载后残留配置

```bash
if [ -d /etc/mysql ]; then
  MYSQL_ETC_REAL="$(readlink -f -- /etc/mysql)"
  if [ "${MYSQL_ETC_REAL}" != "/etc/mysql" ]; then
    echo "ERROR: /etc/mysql 解析为 ${MYSQL_ETC_REAL}，停止执行。"
    exit 1
  fi
  mv -- /etc/mysql "${MYSQL_REINSTALL_BACKUP}/etc-mysql-after-purge"
fi
```

如果卸载过程重新创建了空数据目录，也先隔离：

```bash
if [ -e /var/lib/mysql ]; then
  MYSQL_POST_PURGE_DATA_REAL="$(readlink -f -- /var/lib/mysql)"
  if [ "${MYSQL_POST_PURGE_DATA_REAL}" != "/var/lib/mysql" ]; then
    echo "ERROR: 卸载后的数据目录路径异常：${MYSQL_POST_PURGE_DATA_REAL}"
    exit 1
  fi
  mv -- /var/lib/mysql "${MYSQL_REINSTALL_BACKUP}/datadir-after-purge"
fi
```

## 11. 第八步：配置 Oracle MySQL 8.4 LTS APT 仓库

项目总体方案指定 Oracle MySQL 8.4 LTS，不使用未来可能变化的 Ubuntu 默认 MySQL 版本。

### 11.1 安装仓库配置所需工具

```bash
apt-get update
apt-get install -y ca-certificates curl debconf
```

### 11.2 下载并校验官方仓库配置包

以下版本和校验值来自 2026-08-14 的 MySQL 官方下载页：

```bash
MYSQL_APT_CONFIG_FILE="/tmp/mysql-apt-config_0.8.39-1_all.deb"

curl --fail --location --show-error \
  --output "${MYSQL_APT_CONFIG_FILE}" \
  "https://dev.mysql.com/get/mysql-apt-config_0.8.39-1_all.deb"

echo "8f722bb35fc6f510a2154a9466f5e2f7  ${MYSQL_APT_CONFIG_FILE}" \
  | md5sum --check --strict
```

校验结果必须包含：

```text
OK
```

校验失败时不要安装该文件。

### 11.3 明确选择 MySQL 8.4 LTS

```bash
echo "mysql-apt-config mysql-apt-config/select-server select mysql-8.4-lts" \
  | debconf-set-selections

DEBIAN_FRONTEND=noninteractive dpkg -i "${MYSQL_APT_CONFIG_FILE}"
apt-get update
```

检查仓库配置：

```bash
grep -RhsE '^deb .*repo\.mysql\.com.*mysql-8\.4-lts' \
  /etc/apt/sources.list /etc/apt/sources.list.d 2>/dev/null

apt-cache policy mysql-community-server mysql-server
```

确认即将安装的社区服务端版本：

```bash
MYSQL_APT_CANDIDATE="$(
  apt-cache policy mysql-community-server \
  | awk '/Candidate:/ {print $2; exit}'
)"

echo "MYSQL_APT_CANDIDATE=${MYSQL_APT_CANDIDATE}"

case "${MYSQL_APT_CANDIDATE}" in
  8.4.*)
    echo "MySQL 8.4 LTS 版本检查通过"
    ;;
  *)
    echo "ERROR: 软件源候选版本不是 MySQL 8.4，停止安装。"
    exit 1
    ;;
esac
```

如果候选版本显示 `(none)`，说明软件源没有可用的 `mysql-server`，不要继续执行。

## 12. 第九步：在首次初始化前准备参数

### 12.1 预设 `lower_case_table_names=1`

MySQL 官方 APT 包会在安装期间初始化数据目录，因此必须在安装前执行：

```bash
echo "mysql-server mysql-server/lowercase-table-names select Enabled" \
  | debconf-set-selections
```

### 12.2 创建项目配置文件

```bash
install -d -m 755 /etc/mysql/mysql.conf.d

tee /etc/mysql/mysql.conf.d/99-account-book.cnf >/dev/null <<'EOF'
[mysqld]

# MyTallyBook - initialized settings
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
innodb_buffer_pool_size=512M
log_bin_trust_function_creators=1
lower_case_table_names=1
max_connections=5000
sql_mode=NO_ENGINE_SUBSTITUTION,STRICT_TRANS_TABLES
transaction_isolation=READ-COMMITTED
group_concat_max_len=102400
EOF

chown root:root /etc/mysql/mysql.conf.d/99-account-book.cnf
chmod 644 /etc/mysql/mysql.conf.d/99-account-book.cnf
sed -n '1,120p' /etc/mysql/mysql.conf.d/99-account-book.cnf
```

## 13. 第十步：重新安装 MySQL 8.4 LTS

```bash
apt-get install -y mysql-server
```

安装期间如果要求设置 MySQL root 密码，请使用强密码并保存到密码管理器，不要记录在本文或 Shell 脚本中。

确认安装结果：

```bash
mysql --version
dpkg-query -W -f='${binary:Package}\t${Version}\n' \
  mysql-server mysql-community-server
```

确定服务名称：

```bash
if systemctl list-unit-files mysql.service --no-legend 2>/dev/null | grep -q mysql.service; then
  MYSQL_SERVICE="mysql"
elif systemctl list-unit-files mysqld.service --no-legend 2>/dev/null | grep -q mysqld.service; then
  MYSQL_SERVICE="mysqld"
else
  echo "ERROR: 未找到 mysql.service 或 mysqld.service"
  exit 1
fi

echo "MYSQL_SERVICE=${MYSQL_SERVICE}"
```

## 14. 第十一步：设置文件句柄上限

`max_connections=5000` 需要足够的文件句柄。创建 systemd 覆盖配置：

```bash
install -d -m 755 "/etc/systemd/system/${MYSQL_SERVICE}.service.d"

tee "/etc/systemd/system/${MYSQL_SERVICE}.service.d/override.conf" >/dev/null <<'EOF'
[Service]
LimitNOFILE=65535
EOF

systemctl daemon-reload
systemctl show "${MYSQL_SERVICE}" -p LimitNOFILE
```

## 15. 第十二步：校验配置并重启

```bash
mysqld --validate-config
```

确认上一条命令退出码为 `0`：

```bash
echo $?
```

只有返回 `0` 才继续：

```bash
systemctl enable "${MYSQL_SERVICE}"
systemctl restart "${MYSQL_SERVICE}"
systemctl is-active "${MYSQL_SERVICE}"
systemctl status "${MYSQL_SERVICE}" --no-pager
```

如果重启失败，立即查看：

```bash
journalctl -u "${MYSQL_SERVICE}" -n 200 --no-pager
```

## 16. 第十三步：验证所有参数

Ubuntu 默认通常允许 root 通过本地 Socket 登录：

```bash
mysql --protocol=socket --table -e "
SELECT VERSION() AS mysql_version,
       @@GLOBAL.character_set_server AS character_set_server,
       @@GLOBAL.collation_server AS collation_server,
       @@GLOBAL.innodb_buffer_pool_size AS innodb_buffer_pool_size_bytes,
       ROUND(@@GLOBAL.innodb_buffer_pool_size/1024/1024) AS buffer_pool_mb,
       @@GLOBAL.log_bin_trust_function_creators AS log_bin_trust_function_creators,
       @@GLOBAL.lower_case_table_names AS lower_case_table_names,
       @@GLOBAL.max_connections AS max_connections,
       @@GLOBAL.sql_mode AS sql_mode,
       @@GLOBAL.transaction_isolation AS transaction_isolation,
       @@GLOBAL.group_concat_max_len AS group_concat_max_len,
       @@GLOBAL.open_files_limit AS open_files_limit;
SHOW GLOBAL STATUS LIKE 'Threads_connected';
"
```

必须确认：

```text
character_set_server             utf8mb4
collation_server                 utf8mb4_0900_ai_ci
innodb_buffer_pool_size_bytes    536870912
buffer_pool_mb                   512
log_bin_trust_function_creators  1
lower_case_table_names           1
max_connections                  5000
transaction_isolation            READ-COMMITTED
group_concat_max_len             102400
```

`sql_mode` 中必须同时包含：

```text
NO_ENGINE_SUBSTITUTION
STRICT_TRANS_TABLES
```

两个值的显示顺序可能与配置文件不同。

如果 `lower_case_table_names` 不是 `1`，不要创建业务库或导入数据。先保存以下输出并停止后续操作：

```bash
mysql --protocol=socket -e "SHOW VARIABLES LIKE 'lower_case_table_names';"
journalctl -u "${MYSQL_SERVICE}" -n 200 --no-pager
```

## 17. 第十四步：安全初始化检查

运行官方安全配置向导：

```bash
mysql_secure_installation
```

根据提示完成：

- 设置或确认 root 强密码。
- 删除匿名用户。
- 禁止 root 远程登录。
- 删除测试数据库。
- 重新加载权限表。

如果当前 root 使用 Ubuntu 的 Socket 身份验证且不需要设置密码，仍应确认匿名用户、测试库和远程 root 已被移除。

## 18. 第十五步：创建记账本数据库

如果安全初始化后 root 改为密码认证，请将下列 `mysql --protocol=socket` 改为 `mysql -uroot -p`。

```bash
mysql --protocol=socket <<'SQL'
CREATE DATABASE IF NOT EXISTS account_book
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
SQL
```

验证：

```bash
mysql --protocol=socket --table -e "
SELECT SCHEMA_NAME,
       DEFAULT_CHARACTER_SET_NAME,
       DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME='account_book';
"
```

不要使用 MySQL root 账号连接 Spring Boot。应用专用账号和密码将在后续数据库初始化文档中创建。

## 19. 第十六步：检查端口和日志

```bash
ss -lntp | grep ':3306' || true
systemctl show "${MYSQL_SERVICE}" -p LimitNOFILE
journalctl -u "${MYSQL_SERVICE}" -n 100 --no-pager
```

如果 Spring Boot 和 MySQL 位于同一台服务器，MySQL 推荐只监听 `127.0.0.1:3306`。不要在京东云安全组中向公网开放 3306。

## 20. 安装失败处理

### 20.1 软件包安装失败

```bash
apt-get -f install
dpkg --configure -a
journalctl -xe --no-pager | tail -n 200
```

修复后重新执行：

```bash
apt-get install -y mysql-server
```

### 20.2 MySQL 启动失败

如果出现 `mysqld` 返回 `status=1/FAILURE`，按照专项文档执行只读诊断和分支恢复：[MySQL 8.4 启动失败诊断与恢复](07-MySQL8启动失败诊断与恢复.md)。

```bash
systemctl status "${MYSQL_SERVICE}" --no-pager
journalctl -u "${MYSQL_SERVICE}" -n 200 --no-pager
mysqld --validate-config
```

不要在原因未明确时复制原 `datadir` 覆盖新数据目录，也不要删除备份。

### 20.3 查看本次备份

```bash
echo "${MYSQL_REINSTALL_BACKUP}"
find "${MYSQL_REINSTALL_BACKUP}" -maxdepth 2 -mindepth 1 -ls
gzip -t "${MYSQL_REINSTALL_BACKUP}/full-backup.sql.gz"
```

## 21. 最终验收清单

- [ ] 操作系统确认为 Ubuntu/Debian。
- [ ] 原数据库已经生成可校验的逻辑备份。
- [ ] 原 `/var/lib/mysql` 已移动到备份目录，没有直接删除。
- [ ] 安装版本为 MySQL 8.4.x LTS。
- [ ] MySQL 服务处于 `active` 状态并已开机自启。
- [ ] `lower_case_table_names=1`。
- [ ] `character_set_server=utf8mb4`。
- [ ] `collation_server=utf8mb4_0900_ai_ci`。
- [ ] `innodb_buffer_pool_size=536870912`。
- [ ] `log_bin_trust_function_creators=1`。
- [ ] `max_connections=5000`。
- [ ] `sql_mode` 包含两个指定模式。
- [ ] `transaction_isolation=READ-COMMITTED`。
- [ ] `group_concat_max_len=102400`。
- [ ] `open_files_limit` 足以支持目标连接数。
- [ ] `account_book` 数据库已经创建。
- [ ] 3306 未向公网开放。
- [ ] 备份目录仍然保留。

## 22. 官方参考

- [MySQL APT 安装与卸载](https://dev.mysql.com/doc/refman/8.4/en/linux-installation-apt-repo.html)
- [MySQL APT 仓库官方下载页](https://dev.mysql.com/downloads/repo/apt/)
- [MySQL 数据目录初始化](https://dev.mysql.com/doc/refman/8.4/en/data-directory-initialization.html)
- [MySQL 系统变量及 lower_case_table_names 限制](https://dev.mysql.com/doc/refman/8.0/en/server-system-variables.html)
- [MySQL Unicode 字符集](https://dev.mysql.com/doc/refman/8.4/en/charset-unicode-sets.html)
