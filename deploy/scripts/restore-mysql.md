# MyTallyBook 数据库恢复演练

本流程只用于维护窗口和恢复演练。第一次恢复必须导入临时数据库，禁止把未知备份直接导入 `account_book` 生产库。

## 安全边界

- 使用 root-only 的 MySQL option 文件（例如 `/etc/account-book/backup.cnf`，权限 `0600`）；不要把密码写入命令行、脚本、Git、JAR 或聊天记录。
- 恢复前核对备份文件及 `.sha256`，并执行 `gzip -t`。校验失败立即停止。
- 生产恢复前停止 `account-book.service`，确认 Nginx 不再把请求转发到正在恢复的实例；保留现有 `current.jar` 和最近可用版本。
- 现有公网 80 端口属于其他服务，不得修改或用于 HTTP-01；后端继续只监听 `127.0.0.1:7631`，MySQL 只监听本机 3306。

## 临时库恢复演练

```bash
set -Eeuo pipefail
backup=/var/backups/account-book/account_book_YYYYMMDDTHHMMSSZ.sql.gz
sha256sum -c "$backup.sha256"
gzip -t "$backup"
mysql --defaults-extra-file=/etc/account-book/backup.cnf -e \
  "CREATE DATABASE account_book_restore_drill CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
gzip -dc -- "$backup" | mysql --defaults-extra-file=/etc/account-book/backup.cnf account_book_restore_drill
mysql --defaults-extra-file=/etc/account-book/backup.cnf account_book_restore_drill -e \
  "SELECT COUNT(*) AS tables_count FROM information_schema.tables WHERE table_schema='account_book_restore_drill';"
```

使用与备份对应版本的 JAR 和隔离配置启动一次只读验证，核对 Flyway 历史、账本数和账单数。验证完成后删除临时库，并记录执行人、时间、备份摘要、恢复耗时和核对结果：

```bash
mysql --defaults-extra-file=/etc/account-book/backup.cnf -e \
  'DROP DATABASE account_book_restore_drill;'
```

## 生产恢复（仅获批维护窗口）

1. 记录 `readlink -f /opt/account-book/current.jar`、`systemctl status account-book` 和最近日志。
2. 再做一次当前生产库逻辑备份，并确认备份摘要可读。
3. `systemctl stop account-book.service`，确认 7631 已释放；Nginx 保持配置不变。
4. 经审批后将已核验备份导入生产库；导入过程不接受公网请求。
5. 原子切换 `/opt/account-book/current.jar` 到与数据库兼容的 JAR，启动服务。
6. 依次核对 `curl http://127.0.0.1:7631/actuator/health`、本地 API、HTTPS 入口和错误日志。
7. 任一检查失败，停止服务并按发布回滚流程恢复上一 JAR；数据库结构回滚须使用经过演练的反向迁移或整库恢复，禁止临时手写删除字段。

每月最少完成一次临时库恢复演练；发版包含数据库迁移时，发版前额外执行备份并将结果附在发布记录中。
