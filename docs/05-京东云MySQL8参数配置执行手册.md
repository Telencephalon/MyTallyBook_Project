# 京东云 Linux MySQL 8 参数配置执行手册

> 文档版本：V1.0
>
> 编制日期：2026-08-14
>
> 适用环境：京东云 Linux、自建 MySQL 8、systemd 管理
>
> 执行方式：SSH 登录服务器后执行
> 重要说明：本文不会在已经初始化的实例上强制修改 `lower_case_table_names`

## 1. 目标参数

本次目标值：

```ini
[mysqld]
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
innodb_buffer_pool_size=512M
log_bin_trust_function_creators=1
max_connections=5000
sql_mode=NO_ENGINE_SUBSTITUTION,STRICT_TRANS_TABLES
transaction_isolation=READ-COMMITTED
group_concat_max_len=102400
```

`lower_case_table_names=1` 只在实例当前值已经是 `1` 时写入配置文件。

## 2. 两个必须了解的限制

### 2.1 使用 `utf8mb4`，不使用 `utf8`

MySQL 8 中的 `utf8` 是 `utf8mb3` 的弃用别名，只能保存最多 3 字节的 UTF-8 字符。微信昵称和记账备注可能包含 emoji，需要 4 字节 UTF-8，因此本项目使用：

```ini
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
```

而不是：

```ini
character-set-server=utf8
```

### 2.2 `lower_case_table_names` 不能在初始化后修改

MySQL 8 规定：`lower_case_table_names` 只能在初始化数据目录时设置。

Linux 上常见当前值是：

```text
0
```

如果实例初始化时是 `0`，之后直接在配置文件中加入：

```ini
lower_case_table_names=1
```

MySQL 可能因数据字典值与启动参数不一致而拒绝启动。

本项目所有数据库、表和字段统一使用小写 `snake_case`，因此在 Linux 上保持 `0` 也可以正常运行。只有当前查询结果已经是 `1` 时，本文脚本才会把它写进配置文件。

如果业务硬性要求从 `0` 改为 `1`，必须在完整备份后重新初始化空数据目录并恢复数据，不能通过普通重启修改。该操作具有破坏性，不包含在本文的一键命令中。

如果已经确认可以卸载现有实例并重新初始化，请改用：[Ubuntu 卸载并重新安装 MySQL 8 执行手册](06-Ubuntu卸载重装MySQL8执行手册.md)。

## 3. 执行前准备

### 3.1 SSH 登录

```bash
ssh deploy@服务器公网IP
```

或使用你的实际运维账号。

### 3.2 切换为 root shell

```bash
sudo -i
```

后续命令默认在 root shell 中执行。

### 3.3 MySQL 管理员登录方式

本文命令默认 Ubuntu 上可以使用：

```bash
mysql --protocol=socket
```

先测试：

```bash
mysql --protocol=socket -e "SELECT VERSION();"
```

如果提示 `Access denied`，退出 root shell 后使用：

```bash
mysql -uroot -p
```

或者先创建安全的本地登录路径：

```bash
mysql_config_editor set \
  --login-path=localroot \
  --host=localhost \
  --user=root \
  --password
```

然后将本文所有：

```bash
mysql --protocol=socket
```

替换为：

```bash
mysql --login-path=localroot
```

不要把 MySQL root 密码直接写入脚本、Shell 历史或 Markdown。

## 4. 第一步：只读检查

以下命令不会修改 MySQL。

### 4.1 检查服务名称和状态

```bash
if systemctl is-active --quiet mysql; then
  MYSQL_SERVICE="mysql"
elif systemctl is-active --quiet mysqld; then
  MYSQL_SERVICE="mysqld"
else
  echo "ERROR: mysql/mysqld 服务未运行"
  exit 1
fi

echo "MYSQL_SERVICE=${MYSQL_SERVICE}"
systemctl status "${MYSQL_SERVICE}" --no-pager
```

### 4.2 检查版本和运行目录

```bash
mysql --protocol=socket --table -e "
SELECT
  VERSION() AS mysql_version,
  @@version_comment AS version_comment,
  @@hostname AS hostname,
  @@datadir AS data_directory;
"
```

### 4.3 查看当前参数

```bash
mysql --protocol=socket --table -e "
SELECT @@GLOBAL.character_set_server          AS character_set_server,
       @@GLOBAL.collation_server              AS collation_server,
       @@GLOBAL.innodb_buffer_pool_size       AS innodb_buffer_pool_size_bytes,
       @@GLOBAL.log_bin_trust_function_creators AS log_bin_trust_function_creators,
       @@GLOBAL.lower_case_table_names        AS lower_case_table_names,
       @@GLOBAL.max_connections               AS max_connections,
       @@GLOBAL.sql_mode                      AS sql_mode,
       @@GLOBAL.transaction_isolation          AS transaction_isolation,
       @@GLOBAL.group_concat_max_len          AS group_concat_max_len,
       @@GLOBAL.open_files_limit              AS open_files_limit;
"
```

### 4.4 检查是否存在 `SET PERSIST` 历史值

MySQL 的持久化变量会在普通配置文件之后加载，可能覆盖配置文件。

```bash
mysql --protocol=socket --table -e "
SELECT VARIABLE_NAME, VARIABLE_VALUE
FROM performance_schema.persisted_variables
WHERE VARIABLE_NAME IN (
  'character_set_server',
  'collation_server',
  'innodb_buffer_pool_size',
  'log_bin_trust_function_creators',
  'lower_case_table_names',
  'max_connections',
  'sql_mode',
  'transaction_isolation',
  'group_concat_max_len'
)
ORDER BY VARIABLE_NAME;
"
```

如果没有输出，说明这些参数没有被 `SET PERSIST` 覆盖。

如果查询有输出，请先记录结果，并在执行本文第 12 节“重启 MySQL”之前完成第 14 节，清除与配置文件同名的持久化值。否则 `mysqld-auto.cnf` 中的值可能在启动时覆盖本次配置。

### 4.5 检查服务器内存

```bash
free -h
awk '/MemTotal/ {printf "MemTotal: %.2f GiB\n", $2/1024/1024}' /proc/meminfo
```

`512M` 约等于 2 GiB 系统内存的四分之一。如果服务器是 4 GiB，四分之一约为 1 GiB；但应用、MySQL、Nginx 同机时保留更多系统余量是合理的，因此本次仍按指定值 `512M` 设置。

### 4.6 检查监听端口

```bash
ss -lntp | grep ':3306' || true
```

如果 MySQL 与 Spring Boot 部署在同一台服务器，推荐只监听：

```text
127.0.0.1:3306
```

不要在京东云安全组中向公网开放 3306。

## 5. 第二步：自动确定配置文件位置

执行：

```bash
if [ -d /etc/mysql/mysql.conf.d ]; then
  MYSQL_CNF="/etc/mysql/mysql.conf.d/99-account-book.cnf"
elif [ -d /etc/mysql/conf.d ]; then
  MYSQL_CNF="/etc/mysql/conf.d/99-account-book.cnf"
elif [ -d /etc/my.cnf.d ]; then
  MYSQL_CNF="/etc/my.cnf.d/99-account-book.cnf"
else
  echo "ERROR: 未找到 MySQL 配置包含目录"
  mysqld --verbose --help 2>/dev/null | sed -n '/Default options/,+2p'
  exit 1
fi

echo "MYSQL_CNF=${MYSQL_CNF}"
```

Ubuntu/Debian 通常得到：

```text
/etc/mysql/mysql.conf.d/99-account-book.cnf
```

Rocky Linux、AlmaLinux、CentOS Stream 通常得到：

```text
/etc/my.cnf.d/99-account-book.cnf
```

## 6. 第三步：备份现有项目配置

```bash
CONFIG_STAMP="$(date +%Y%m%d_%H%M%S)"

if [ -f "${MYSQL_CNF}" ]; then
  cp -a "${MYSQL_CNF}" "${MYSQL_CNF}.bak.${CONFIG_STAMP}"
  echo "Backup: ${MYSQL_CNF}.bak.${CONFIG_STAMP}"
else
  echo "No existing project config: ${MYSQL_CNF}"
fi
```

同时创建数据库逻辑备份目录：

```bash
install -d -m 700 /var/backups/mysql-before-tuning
```

如果当前已经有业务数据，先备份所有业务库。以下示例备份 `account_book`；数据库尚未创建时跳过：

```bash
if mysql --protocol=socket -Nse \
  "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='account_book'" \
  | grep -qx account_book; then
  mysqldump --protocol=socket \
    --single-transaction \
    --routines \
    --triggers \
    --events \
    --set-gtid-purged=OFF \
    account_book \
    | gzip > "/var/backups/mysql-before-tuning/account_book_${CONFIG_STAMP}.sql.gz"

  gzip -t "/var/backups/mysql-before-tuning/account_book_${CONFIG_STAMP}.sql.gz"
  sha256sum "/var/backups/mysql-before-tuning/account_book_${CONFIG_STAMP}.sql.gz"
fi
```

## 7. 第四步：读取 `lower_case_table_names`

```bash
CURRENT_LOWER_CASE="$(
  mysql --protocol=socket --batch --skip-column-names \
    -e "SELECT @@GLOBAL.lower_case_table_names;" \
    | tr -d '[:space:]'
)"

echo "CURRENT_LOWER_CASE=${CURRENT_LOWER_CASE}"

case "${CURRENT_LOWER_CASE}" in
  1)
    echo "lower_case_table_names 已经是 1，可以在配置文件中保持 1"
    ;;
  0|2)
    echo "WARNING: 当前值是 ${CURRENT_LOWER_CASE}，初始化后禁止修改，将保持当前值"
    ;;
  *)
    echo "ERROR: 无法识别 lower_case_table_names=${CURRENT_LOWER_CASE}"
    exit 1
    ;;
esac
```

## 8. 第五步：写入配置文件

先写入可安全修改的参数：

```bash
tee "${MYSQL_CNF}" >/dev/null <<'EOF'
[mysqld]

# MyTallyBook - managed settings
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
innodb_buffer_pool_size=512M
log_bin_trust_function_creators=1
max_connections=5000
sql_mode=NO_ENGINE_SUBSTITUTION,STRICT_TRANS_TABLES
transaction_isolation=READ-COMMITTED
group_concat_max_len=102400
EOF
```

只有当前值已经为 `1` 时才追加：

```bash
if [ "${CURRENT_LOWER_CASE}" = "1" ]; then
  printf '\nlower_case_table_names=1\n' >> "${MYSQL_CNF}"
fi
```

设置权限并查看：

```bash
chown root:root "${MYSQL_CNF}"
chmod 644 "${MYSQL_CNF}"
sed -n '1,120p' "${MYSQL_CNF}"
```

## 9. 第六步：检查配置语法

```bash
mysqld --validate-config
```

预期退出码为 `0`：

```bash
echo $?
```

如果 `mysqld --validate-config` 因发行版权限策略要求使用 MySQL 用户，则执行：

```bash
sudo -u mysql mysqld --validate-config
```

配置检查失败时不要重启 MySQL，直接进入本文“失败回滚”章节。

## 10. 第七步：在线应用动态参数

以下设置立即影响新连接或全局运行值，无需先重启：

```bash
mysql --protocol=socket <<'SQL'
SET GLOBAL character_set_server = 'utf8mb4';
SET GLOBAL collation_server = 'utf8mb4_0900_ai_ci';
SET GLOBAL innodb_buffer_pool_size = 536870912;
SET GLOBAL log_bin_trust_function_creators = 1;
SET GLOBAL max_connections = 5000;
SET GLOBAL sql_mode = 'NO_ENGINE_SUBSTITUTION,STRICT_TRANS_TABLES';
SET GLOBAL transaction_isolation = 'READ-COMMITTED';
SET GLOBAL group_concat_max_len = 102400;
SQL
```

说明：

- `536870912` 字节等于 `512M`。
- 修改全局字符集只影响之后创建的数据库以及显式使用服务器默认值的对象。
- 修改全局事务隔离级别只影响之后建立的会话，现有连接需要重连。
- 修改全局 `group_concat_max_len` 只影响之后建立的会话；应用连接池重连后生效。
- `lower_case_table_names` 不是动态参数，不执行 `SET GLOBAL`。

## 11. 第八步：在线验证

```bash
mysql --protocol=socket --table -e "
SELECT @@GLOBAL.character_set_server          AS character_set_server,
       @@GLOBAL.collation_server              AS collation_server,
       @@GLOBAL.innodb_buffer_pool_size       AS innodb_buffer_pool_size_bytes,
       ROUND(@@GLOBAL.innodb_buffer_pool_size/1024/1024) AS buffer_pool_mb,
       @@GLOBAL.log_bin_trust_function_creators AS log_bin_trust_function_creators,
       @@GLOBAL.lower_case_table_names        AS lower_case_table_names,
       @@GLOBAL.max_connections               AS max_connections,
       @@GLOBAL.sql_mode                      AS sql_mode,
       @@GLOBAL.transaction_isolation          AS transaction_isolation,
       @@GLOBAL.group_concat_max_len          AS group_concat_max_len,
       @@GLOBAL.open_files_limit              AS open_files_limit;
"
```

检查 Buffer Pool 在线调整状态：

```bash
mysql --protocol=socket --table -e \
  "SHOW STATUS LIKE 'Innodb_buffer_pool_resize_status';"
```

预期关键结果：

```text
character_set_server             utf8mb4
collation_server                 utf8mb4_0900_ai_ci
innodb_buffer_pool_size_bytes    536870912
buffer_pool_mb                   512
log_bin_trust_function_creators  1
max_connections                  5000
sql_mode                         STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION
transaction_isolation            READ-COMMITTED
group_concat_max_len             102400
```

`sql_mode` 显示顺序与配置顺序不同不影响结果。

## 12. 第九步：重启验证持久配置

在线设置已经生效，但仍需在维护窗口重启一次，确认配置文件能在启动时加载。

先确认当前没有重要长事务：

```bash
mysql --protocol=socket --table -e \
  "SELECT trx_id, trx_started, trx_mysql_thread_id, trx_query FROM information_schema.innodb_trx;"
```

没有重要事务后执行：

```bash
systemctl restart "${MYSQL_SERVICE}"
systemctl is-active "${MYSQL_SERVICE}"
systemctl status "${MYSQL_SERVICE}" --no-pager
```

再次验证：

```bash
mysql --protocol=socket --table -e "
SELECT @@GLOBAL.character_set_server          AS character_set_server,
       @@GLOBAL.collation_server              AS collation_server,
       @@GLOBAL.innodb_buffer_pool_size       AS innodb_buffer_pool_size_bytes,
       @@GLOBAL.log_bin_trust_function_creators AS log_bin_trust_function_creators,
       @@GLOBAL.lower_case_table_names        AS lower_case_table_names,
       @@GLOBAL.max_connections               AS max_connections,
       @@GLOBAL.sql_mode                      AS sql_mode,
       @@GLOBAL.transaction_isolation          AS transaction_isolation,
       @@GLOBAL.group_concat_max_len          AS group_concat_max_len,
       @@GLOBAL.open_files_limit              AS open_files_limit;
"
```

查看启动日志：

```bash
journalctl -u "${MYSQL_SERVICE}" -n 100 --no-pager
```

## 13. `max_connections=5000` 专项检查

MySQL 8 的实际最大连接数还受 `open_files_limit` 等资源限制。检查：

```bash
mysql --protocol=socket --table -e "
SELECT @@GLOBAL.max_connections  AS max_connections,
       @@GLOBAL.open_files_limit AS open_files_limit;
SHOW GLOBAL STATUS LIKE 'Threads_connected';
"
```

操作系统限制：

```bash
systemctl show "${MYSQL_SERVICE}" -p LimitNOFILE
```

对于本项目最多 10 个微信用户，Spring Boot Hikari 连接池建议：

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
```

因此 MySQL `max_connections=5000` 只是连接上限，并不代表应用要创建 5000 个连接。如果没有外部标准强制要求，实际生产建议将 MySQL 上限改为 `300`：

```bash
mysql --protocol=socket -e "SET GLOBAL max_connections = 300;"
sed -i 's/^max_connections=5000$/max_connections=300/' "${MYSQL_CNF}"
```

修改后重新执行配置检查，并在维护窗口重启验证。

## 14. 处理已有 `SET PERSIST` 冲突

如果第 4.4 节查询到了同名持久化变量，它们可能覆盖本文配置文件。先逐项核对，再只清除本次由配置文件管理的变量：

```sql
RESET PERSIST IF EXISTS character_set_server;
RESET PERSIST IF EXISTS collation_server;
RESET PERSIST IF EXISTS innodb_buffer_pool_size;
RESET PERSIST IF EXISTS log_bin_trust_function_creators;
RESET PERSIST IF EXISTS max_connections;
RESET PERSIST IF EXISTS sql_mode;
RESET PERSIST IF EXISTS transaction_isolation;
RESET PERSIST IF EXISTS group_concat_max_len;
```

执行方式：

```bash
mysql --protocol=socket <<'SQL'
RESET PERSIST IF EXISTS character_set_server;
RESET PERSIST IF EXISTS collation_server;
RESET PERSIST IF EXISTS innodb_buffer_pool_size;
RESET PERSIST IF EXISTS log_bin_trust_function_creators;
RESET PERSIST IF EXISTS max_connections;
RESET PERSIST IF EXISTS sql_mode;
RESET PERSIST IF EXISTS transaction_isolation;
RESET PERSIST IF EXISTS group_concat_max_len;
SQL
```

不要对 `lower_case_table_names` 执行持久化修改。

清除后重启一次，并重新验证全局值。

## 15. 创建记账本数据库

如果数据库还没有创建，执行：

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
WHERE SCHEMA_NAME = 'account_book';
"
```

如果数据库已经存在但默认字符集不是 `utf8mb4`，以下命令只修改数据库以后新建对象的默认值，不会自动转换已有表和列：

```bash
mysql --protocol=socket -e "
ALTER DATABASE account_book
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
"
```

已有表的字符集转换可能锁表，必须先评估并备份，不要在生产高峰批量执行。

## 16. 失败回滚

### 16.1 配置语法检查失败

如果原来有备份：

```bash
cp -a "${MYSQL_CNF}.bak.${CONFIG_STAMP}" "${MYSQL_CNF}"
mysqld --validate-config
```

如果原来没有该项目配置文件，将失败文件移动为禁用文件：

```bash
mv "${MYSQL_CNF}" "${MYSQL_CNF}.failed.${CONFIG_STAMP}"
mysqld --validate-config
```

### 16.2 重启失败

先查看日志：

```bash
systemctl status "${MYSQL_SERVICE}" --no-pager
journalctl -u "${MYSQL_SERVICE}" -n 200 --no-pager
```

恢复原配置或禁用新配置：

```bash
if [ -f "${MYSQL_CNF}.bak.${CONFIG_STAMP}" ]; then
  cp -a "${MYSQL_CNF}.bak.${CONFIG_STAMP}" "${MYSQL_CNF}"
else
  mv "${MYSQL_CNF}" "${MYSQL_CNF}.failed.${CONFIG_STAMP}"
fi

systemctl restart "${MYSQL_SERVICE}"
systemctl status "${MYSQL_SERVICE}" --no-pager
```

### 16.3 在线参数回退

如需在线回到本次修改前的值，应使用第 4.3 节执行前记录的原值逐项 `SET GLOBAL`，不要猜测默认值。

## 17. 一次性执行顺序

不建议不看输出地整段粘贴。请严格按以下顺序执行，每步成功后再继续：

1. 执行第 4 节只读检查并保存输出。
2. 确认 `lower_case_table_names` 当前值。
3. 确认服务器内存足以使用 512M Buffer Pool。
4. 确认 3306 未暴露公网。
5. 确定配置文件路径。
6. 备份旧配置和已有业务数据。
7. 写入配置文件。
8. 执行 `mysqld --validate-config`。
9. 在线应用动态参数。
10. 在线检查全部变量。
11. 如果第 4.4 节查询有输出，按第 14 节清除同名持久化值。
12. 在维护窗口重启 MySQL。
13. 重启后再次检查变量和日志。
14. 创建或检查 `account_book` 数据库字符集。

## 18. 最终验收清单

- [ ] MySQL 服务处于 `active`。
- [ ] MySQL 为 8.x。
- [ ] 配置文件已备份。
- [ ] 已有业务数据已做逻辑备份。
- [ ] `mysqld --validate-config` 返回 0。
- [ ] `character_set_server=utf8mb4`。
- [ ] `collation_server=utf8mb4_0900_ai_ci`。
- [ ] `innodb_buffer_pool_size=536870912`。
- [ ] `log_bin_trust_function_creators=1`。
- [ ] `lower_case_table_names` 未在初始化后被强制修改。
- [ ] `max_connections=5000`，或根据实际确认改为 300。
- [ ] `sql_mode` 包含 `STRICT_TRANS_TABLES` 与 `NO_ENGINE_SUBSTITUTION`。
- [ ] `transaction_isolation=READ-COMMITTED`。
- [ ] `group_concat_max_len=102400`。
- [ ] 重启后所有参数仍然正确。
- [ ] `account_book` 使用 `utf8mb4_0900_ai_ci`。
- [ ] 3306 未向公网开放。

## 19. 官方参考

- [MySQL 8.4 Unicode 字符集](https://dev.mysql.com/doc/refman/8.4/en/charset-unicode-sets.html)
- [MySQL 8.4 系统变量](https://dev.mysql.com/doc/refman/8.4/en/server-system-variables.html)
- [MySQL 8.4 动态系统变量](https://dev.mysql.com/doc/refman/8.4/en/dynamic-system-variables.html)
- [MySQL 8.4 使用和持久化系统变量](https://dev.mysql.com/doc/refman/8.4/en/using-system-variables.html)
- [MySQL 8.4 InnoDB Buffer Pool 参数](https://dev.mysql.com/doc/refman/8.4/en/innodb-parameters.html)
- [MySQL 标识符大小写规则](https://dev.mysql.com/doc/refman/8.0/en/identifier-case-sensitivity.html)
