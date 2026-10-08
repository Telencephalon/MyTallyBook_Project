#!/usr/bin/env bash
# Run on the database host. Preview is the default; apply requires --mode apply.
set -Eeuo pipefail
umask 077

MODE=preview
DATABASE=account_book_dev
LEDGER_ID=1
USER_ID=NULL
USER_ID_SPECIFIED=0
MYSQL=/opt/mysql8/bin/mysql
SOCKET=/opt/mysql8/run/mysql.sock
MYSQL_USER=root

usage() {
  cat <<'USAGE'
Usage: bash cleanup-departed-members.sh [options]
  --mode preview|apply        Default: preview (no committed changes)
  --database NAME             Default: account_book_dev
  --ledger-id 1               Only the current fixed ledger is supported
  --user-id ID                Optional: clean only this departed user
  --mysql PATH                Default: /opt/mysql8/bin/mysql
  --socket PATH               Default: /opt/mysql8/run/mysql.sock
  --mysql-user NAME           Default: root (password prompted by mysql)
USAGE
}
abort() { printf '失败：%s\n' "$*" >&2; exit 1; }
while (( $# )); do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --mode|--database|--ledger-id|--user-id|--mysql|--socket|--mysql-user)
      (( $# >= 2 )) && [[ -n "$2" ]] || abort "参数 $1 缺少值"
      case "$1" in
        --mode) MODE=$2 ;; --database) DATABASE=$2 ;; --ledger-id) LEDGER_ID=$2 ;;
        --user-id) USER_ID=$2; USER_ID_SPECIFIED=1 ;; --mysql) MYSQL=$2 ;; --socket) SOCKET=$2 ;;
        --mysql-user) MYSQL_USER=$2 ;;
      esac
      shift 2 ;;
    *) abort "未知参数：$1" ;;
  esac
done
[[ "$MODE" == preview || "$MODE" == apply ]] || abort 'mode 只能是 preview 或 apply'
[[ "$DATABASE" =~ ^[A-Za-z][A-Za-z0-9_]{0,63}$ ]] || abort '数据库名称不合法'
[[ "$MYSQL_USER" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,31}$ ]] || abort 'MySQL 用户名不合法'
[[ "$LEDGER_ID" == 1 ]] || abort '当前应用只支持账本 ID 1'
if (( USER_ID_SPECIFIED )); then
  [[ "$USER_ID" =~ ^[1-9][0-9]{0,19}$ ]] || abort 'user-id 必须是正整数'
  if (( ${#USER_ID} == 20 )) && [[ "$USER_ID" > 18446744073709551615 ]]; then
    abort 'user-id 超过 BIGINT UNSIGNED 范围'
  fi
fi
command -v "$MYSQL" >/dev/null 2>&1 || abort "找不到 MySQL 客户端：$MYSQL"
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
SQL_FILE="$SCRIPT_DIR/../sql/cleanup-departed-members.sql"
[[ -s "$SQL_FILE" ]] || abort "SQL 文件缺失或为空：$SQL_FILE"
APPLY=0
[[ "$MODE" != apply ]] || APPLY=1
printf '模式：%s；数据库：%s；账本：%s；指定用户：%s\n' "$MODE" "$DATABASE" "$LEDGER_ID" "$USER_ID"
printf '只清理 LEFT/REMOVED 的非所有者成员关联；保留账号和历史账单。\n'
if {
  printf "SET @cleanup_apply=%s, @cleanup_expected_database='%s', @cleanup_ledger_id=%s, @cleanup_user_id=%s;\n" \
    "$APPLY" "$DATABASE" "$LEDGER_ID" "$USER_ID"
  cat -- "$SQL_FILE"
} | "$MYSQL" --no-defaults --protocol=SOCKET --socket="$SOCKET" --user="$MYSQL_USER" \
    --password --batch --raw --show-warnings --skip-reconnect --connect-timeout=10 \
    --default-character-set=utf8mb4 --database="$DATABASE"; then
  printf '完成：%s 模式。清理结果见上方 result 与 deleted_members。\n' "$MODE"
else
  status=$?
  printf '失败：数据库命令退出码 %s。未提交的事务会随连接关闭回滚；请检查上方错误。\n' "$status" >&2
  exit "$status"
fi
