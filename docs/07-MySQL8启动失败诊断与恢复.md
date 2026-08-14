# MySQL 8.4 启动失败诊断与恢复

> 文档版本：V1.0
>
> 编制日期：2026-08-14
>
> 当前现象：`mysql.service` 的 `mysqld` 主进程退出，状态为 `status=1/FAILURE`

## 1. 当前判断

截图中的 systemd 日志只说明 `mysqld` 启动失败，没有包含 MySQL 自己记录的根本错误。

结合本次重装和参数修改，优先怀疑：

```text
旧数据目录初始化值：lower_case_table_names=0
当前配置文件设置值：lower_case_table_names=1
```

MySQL 8 不允许启动参数和数据字典初始化值不同，出现这种情况时会拒绝启动。

在看到 MySQL 错误日志前，不要删除 `/var/lib/mysql`，不要重复执行卸载，也不要反复重启。

## 2. 第一步：执行只读诊断

进入 root shell：

```bash
sudo -i
```

执行以下整组命令：

```bash
echo '===== 1. MySQL version ====='
mysql --version || true

echo '===== 2. Service status ====='
systemctl status mysql --no-pager -l || true

echo '===== 3. Configuration validation ====='
mysqld --validate-config 2>&1 || true

echo '===== 4. Project configuration ====='
grep -RnsE \
  '^[[:space:]]*(character-set-server|collation-server|innodb_buffer_pool_size|log_bin_trust_function_creators|lower_case_table_names|max_connections|sql_mode|transaction_isolation|group_concat_max_len)[[:space:]]*=' \
  /etc/mysql 2>/dev/null || true

echo '===== 5. Error log: /var/log/mysql/error.log ====='
if [ -f /var/log/mysql/error.log ]; then
  tail -n 200 /var/log/mysql/error.log
else
  echo 'not found'
fi

echo '===== 6. Error log: /var/log/mysqld.log ====='
if [ -f /var/log/mysqld.log ]; then
  tail -n 200 /var/log/mysqld.log
else
  echo 'not found'
fi

echo '===== 7. Error log in data directory ====='
find /var/lib/mysql -maxdepth 1 -type f -name '*.err' \
  -exec tail -n 200 {} \; 2>/dev/null || true

echo '===== 8. systemd journal ====='
journalctl -u mysql -n 200 --no-pager -o short-iso || true

echo '===== 9. Data directory ====='
readlink -f /var/lib/mysql || true
stat -c '%U:%G %a %n' /var/lib/mysql 2>/dev/null || true
find /var/lib/mysql -maxdepth 1 -mindepth 1 -printf '%f\n' \
  2>/dev/null | sort | head -n 80

echo '===== 10. Memory and disk ====='
free -h
df -h /var/lib/mysql /var/log 2>/dev/null || true

echo '===== 11. Kernel OOM messages ====='
dmesg -T 2>/dev/null \
  | grep -iE 'out of memory|oom-killer|killed process' \
  | tail -n 50 || true
```

重点查找以下内容：

```text
Different lower_case_table_names settings
unknown variable
Permission denied
Can't create/write to file
Data Dictionary initialization failed
No space left on device
Out of memory
```

## 3. 分支 A：确认是 `lower_case_table_names` 不一致

典型错误包含：

```text
Different lower_case_table_names settings for server ('1') and data dictionary ('0')
```

此时只能二选一。

### 3.1 保留原数据，先按值 `0` 恢复启动

适用于原数据必须保留，或者尚未确认备份有效的情况。

备份配置：

```bash
MYSQL_RECOVERY_STAMP="$(date +%Y%m%d_%H%M%S)"

cp -a /etc/mysql/mysql.conf.d/99-account-book.cnf \
  "/etc/mysql/mysql.conf.d/99-account-book.cnf.bak.${MYSQL_RECOVERY_STAMP}"
```

仅删除配置中的这一行：

```bash
sed -i '/^[[:space:]]*lower_case_table_names[[:space:]]*=/d' \
  /etc/mysql/mysql.conf.d/99-account-book.cnf
```

检查并启动：

```bash
mysqld --validate-config
systemctl restart mysql
systemctl status mysql --no-pager -l
```

启动成功后验证：

```bash
mysql --protocol=socket --table -e "
SELECT @@GLOBAL.lower_case_table_names AS lower_case_table_names,
       @@GLOBAL.character_set_server AS character_set_server,
       @@GLOBAL.innodb_buffer_pool_size AS innodb_buffer_pool_size,
       @@GLOBAL.max_connections AS max_connections;
"
```

此路径预计得到 `lower_case_table_names=0`。只要项目所有库名和表名统一使用小写，就可以正常运行。

### 3.2 确认不需要旧数据，重新初始化为值 `1`

仅在满足以下任一条件时执行：

- 这是刚安装的空实例，没有业务数据。
- 已完成并校验逻辑备份，明确接受重新初始化数据目录。

先验证配置中确实只有一个目标值：

```bash
grep -RnsE '^[[:space:]]*lower_case_table_names[[:space:]]*=' \
  /etc/mysql 2>/dev/null
```

预期只有：

```text
lower_case_table_names=1
```

校验配置：

```bash
mysqld --validate-config
```

设置备份位置：

```bash
MYSQL_REINIT_STAMP="$(date +%Y%m%d_%H%M%S)"
MYSQL_REINIT_BACKUP="/var/backups/mysql-failed-init-${MYSQL_REINIT_STAMP}"

install -d -m 700 "${MYSQL_REINIT_BACKUP}"

MYSQL_REINIT_BACKUP_REAL="$(readlink -f -- "${MYSQL_REINIT_BACKUP}")"
case "${MYSQL_REINIT_BACKUP_REAL}" in
  /var/backups/mysql-failed-init-*)
    echo "备份路径检查通过：${MYSQL_REINIT_BACKUP_REAL}"
    ;;
  *)
    echo "ERROR: 备份路径异常：${MYSQL_REINIT_BACKUP_REAL}"
    exit 1
    ;;
esac
```

输入人工确认：

```bash
read -r -p "确认重新初始化空实例，输入 REINITIALIZE MYSQL：" MYSQL_REINIT_CONFIRM

if [ "${MYSQL_REINIT_CONFIRM}" != "REINITIALIZE MYSQL" ]; then
  echo '未确认，操作终止。'
  exit 1
fi
```

停止 MySQL：

```bash
systemctl stop mysql 2>/dev/null || true

if systemctl is-active --quiet mysql; then
  echo 'ERROR: MySQL 仍在运行，禁止移动数据目录。'
  exit 1
fi
```

严格检查并移动失败的数据目录，不直接删除：

```bash
if [ -e /var/lib/mysql ]; then
  MYSQL_FAILED_DATA_REAL="$(readlink -f -- /var/lib/mysql)"

  if [ "${MYSQL_FAILED_DATA_REAL}" != "/var/lib/mysql" ]; then
    echo "ERROR: /var/lib/mysql 解析为 ${MYSQL_FAILED_DATA_REAL}，停止执行。"
    exit 1
  fi

  mv -- /var/lib/mysql "${MYSQL_REINIT_BACKUP}/datadir"
fi
```

创建空目录：

```bash
install -d -o mysql -g mysql -m 750 /var/lib/mysql
install -d -o mysql -g mysql -m 750 /var/lib/mysql-files
```

初始化。`--defaults-file` 必须是第一个参数：

```bash
mysqld \
  --defaults-file=/etc/mysql/my.cnf \
  --initialize \
  --user=mysql \
  --datadir=/var/lib/mysql
```

初始化成功后启动：

```bash
systemctl start mysql
systemctl status mysql --no-pager -l
```

查找临时 root 密码：

```bash
grep -h 'temporary password' \
  /var/log/mysql/error.log \
  /var/log/mysqld.log \
  /var/lib/mysql/*.err \
  2>/dev/null \
  | tail -n 1
```

使用临时密码登录并立即修改密码：

```bash
mysql --connect-expired-password -uroot -p
```

进入 MySQL 后执行，手工替换强密码占位符，不要把真实密码保存进 Markdown：

```sql
ALTER USER 'root'@'localhost' IDENTIFIED BY '替换为实际强密码';
EXIT;
```

重新登录并验证：

```bash
mysql -uroot -p --table -e "
SELECT VERSION() AS mysql_version,
       @@GLOBAL.lower_case_table_names AS lower_case_table_names,
       @@GLOBAL.character_set_server AS character_set_server,
       @@GLOBAL.collation_server AS collation_server,
       @@GLOBAL.innodb_buffer_pool_size AS innodb_buffer_pool_size,
       @@GLOBAL.max_connections AS max_connections,
       @@GLOBAL.sql_mode AS sql_mode,
       @@GLOBAL.transaction_isolation AS transaction_isolation,
       @@GLOBAL.group_concat_max_len AS group_concat_max_len;
"
```

## 4. 分支 B：配置语法或未知参数错误

如果 `mysqld --validate-config` 明确报告：

```text
unknown variable
option ... was not recognized
```

记录错误中给出的配置项和文件位置，只修改该错误项，然后反复执行：

```bash
mysqld --validate-config
```

退出码必须为 `0`：

```bash
echo $?
```

然后启动：

```bash
systemctl restart mysql
systemctl status mysql --no-pager -l
```

不要为了绕过语法错误直接清空整个配置文件，因为数据目录可能已经使用 `lower_case_table_names=1` 初始化。

## 5. 分支 C：权限错误

只有错误日志明确包含 `/var/lib/mysql` 的 `Permission denied` 或属主异常时才执行。

先确认目标路径：

```bash
MYSQL_PERMISSION_PATH="$(readlink -f -- /var/lib/mysql)"

if [ "${MYSQL_PERMISSION_PATH}" != "/var/lib/mysql" ]; then
  echo "ERROR: 数据路径异常：${MYSQL_PERMISSION_PATH}"
  exit 1
fi

stat -c '%U:%G %a %n' /var/lib/mysql
find /var/lib/mysql -maxdepth 1 -printf '%U:%G %m %p\n' | head -n 50
```

确认路径正确后修复属主：

```bash
chown -R mysql:mysql /var/lib/mysql
chmod 750 /var/lib/mysql
systemctl restart mysql
systemctl status mysql --no-pager -l
```

## 6. 分支 D：磁盘或内存不足

查看资源：

```bash
df -h
df -i
free -h
dmesg -T 2>/dev/null \
  | grep -iE 'out of memory|oom-killer|killed process' \
  | tail -n 50 || true
```

如果错误是 Buffer Pool 内存不足，可以临时调整为 `256M`：

```bash
cp -a /etc/mysql/mysql.conf.d/99-account-book.cnf \
  "/etc/mysql/mysql.conf.d/99-account-book.cnf.bak.$(date +%Y%m%d_%H%M%S)"

sed -i 's/^innodb_buffer_pool_size=512M$/innodb_buffer_pool_size=256M/' \
  /etc/mysql/mysql.conf.d/99-account-book.cnf

mysqld --validate-config
systemctl restart mysql
```

## 7. 启动成功后的完整验收

```bash
systemctl is-active mysql
systemctl is-enabled mysql

mysql -uroot -p --table -e "
SELECT VERSION() AS mysql_version,
       @@GLOBAL.character_set_server AS character_set_server,
       @@GLOBAL.collation_server AS collation_server,
       @@GLOBAL.innodb_buffer_pool_size AS innodb_buffer_pool_size,
       @@GLOBAL.log_bin_trust_function_creators AS log_bin_trust_function_creators,
       @@GLOBAL.lower_case_table_names AS lower_case_table_names,
       @@GLOBAL.max_connections AS max_connections,
       @@GLOBAL.sql_mode AS sql_mode,
       @@GLOBAL.transaction_isolation AS transaction_isolation,
       @@GLOBAL.group_concat_max_len AS group_concat_max_len,
       @@GLOBAL.open_files_limit AS open_files_limit;
"
```

如果 root 使用 Socket 身份验证，将最后一组命令中的 `mysql -uroot -p` 改成：

```bash
mysql --protocol=socket
```

## 8. 官方参考

- [MySQL 8.4 服务器配置校验](https://dev.mysql.com/doc/refman/8.4/en/server-configuration-validation.html)
- [MySQL 8.4 启动故障排查](https://dev.mysql.com/doc/refman/8.4/en/starting-server.html)
- [MySQL 8.4 服务器日志](https://dev.mysql.com/doc/refman/8.4/en/server-logs.html)
- [MySQL lower_case_table_names 限制](https://dev.mysql.com/doc/refman/8.0/en/server-system-variables.html)

