#!/usr/bin/env bash
# Upload this script into /root/server-upload-20261008-101349 and run with bash.
# No database/config backup or preliminary service stop. Old JARs are retained.
set -Eeuo pipefail
umask 077

VERSION='20261008-101349'
JAR="account-book-server-$VERSION.jar"
PACKAGE_DIR=${ACCOUNT_BOOK_PACKAGE_DIR:-/root/server-upload-20261008-101349}
RELEASE_ROOT=${ACCOUNT_BOOK_RELEASE_ROOT:-/opt/account-book}
RECORD_ROOT=${ACCOUNT_BOOK_RECORD_ROOT:-/root}
EXPECTED_SHA=${ACCOUNT_BOOK_EXPECTED_SHA256:-bff182ec1d9c7d3b15d890548c490539b6e9aa6f5326e31688c000ba0e1ae852}
WAIT_SECONDS=${ACCOUNT_BOOK_WAIT_SECONDS:-120}
SERVICE='account-book.service'
HEALTH_URL='http://127.0.0.1:7631/actuator/health'
CURRENT="$RELEASE_ROOT/current.jar"
NEW="$RELEASE_ROOT/releases/$JAR"
LOCK="$RELEASE_ROOT/.update-backend.lock"
MAIN_BASHPID=$BASHPID
STAGE='初始化'
LOCK_OWNED=0
SWITCH_ATTEMPTED=0
TMP_JAR=''
TMP_LINK=''
OLD=''
RECORD=''
HEALTH=''

log() { printf '%s\n' "$*"; }
abort() { log "失败：$*" >&2; exit 1; }
normalized_directory() {
  [[ "$1" =~ ^/[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$ ]] &&
    [[ "$1" != *'/../'* && "$1" != */.. && "$1" != *'/./'* && "$1" != */. ]]
}
verify_jar() {
  [[ -f "$1" && ! -L "$1" ]]
  printf '%s  %s\n' "$EXPECTED_SHA" "$1" | sha256sum -c -
}
wait_ready() {
  local deadline=$((SECONDS + WAIT_SECONDS))
  while (( SECONDS < deadline )); do
    if HEALTH=$(curl -fsS --connect-timeout 2 --max-time 3 "$HEALTH_URL" 2>/dev/null); then
      if printf '%s' "$HEALTH" | grep -Eq '^\{[[:space:]]*"status"[[:space:]]*:[[:space:]]*"UP"[[:space:]]*\}[[:space:]]*$'; then
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}
cleanup() {
  [[ "$BASHPID" == "$MAIN_BASHPID" ]] || return 0
  [[ -z "$TMP_JAR" ]] || rm -f -- "$TMP_JAR" || true
  [[ -z "$TMP_LINK" ]] || rm -f -- "$TMP_LINK" || true
  if (( LOCK_OWNED )); then rmdir -- "$LOCK" 2>/dev/null || true; fi
}
rollback() {
  # This function is called in an if; each fallible command is checked explicitly.
  [[ -n "$OLD" && "$OLD" != "$NEW" && -f "$OLD" ]] || return 1
  sha256sum -c "$RECORD/previous-jar.sha256" || return 1
  runuser -u accountbook -- test -r "$OLD" || return 1
  [[ -L "$CURRENT" && "$(readlink -f "$CURRENT")" == "$NEW" ]] || return 1
  TMP_LINK="$RELEASE_ROOT/.current.jar.rollback.$$"
  [[ ! -e "$TMP_LINK" && ! -L "$TMP_LINK" ]] || return 1
  ln -s -- "$OLD" "$TMP_LINK" || return 1
  mv -fT -- "$TMP_LINK" "$CURRENT" || return 1
  TMP_LINK=''
  systemctl reset-failed "$SERVICE" || return 1
  systemctl restart "$SERVICE" || return 1
  wait_ready || return 1
  systemctl is-active "$SERVICE" || return 1
  [[ "$(readlink -f "$CURRENT")" == "$OLD" ]] || return 1
  log "已回退并恢复旧版本：$OLD"
  log "$HEALTH"
}
on_error() {
  local status=$1 line=$2 failed_command=$3
  # Command substitutions inherit ERR under set -E. Recover once in the main shell.
  [[ "$BASHPID" == "$MAIN_BASHPID" ]] || exit "$status"
  trap - ERR INT TERM
  set +e
  printf '失败：%s，第%s行，退出码%s\n命令：%s\n' "$STAGE" "$line" "$status" "$failed_command" >&2
  if (( SWITCH_ATTEMPTED )) && [[ "$(readlink -f "$CURRENT" 2>/dev/null)" == "$NEW" ]]; then
    if [[ "$OLD" != "$NEW" ]]; then
      log '正在自动回退到更新前的 JAR……' >&2
      if ! rollback; then
        log "自动回退未恢复健康，请检查日志与旧版本记录：$RECORD" >&2
      fi
    else
      log '更新前已是目标 JAR，没有不同的旧版本可自动回退。' >&2
    fi
  fi
  [[ -z "$RECORD" ]] || log "旧版本记录：$RECORD" >&2
  if (( SWITCH_ATTEMPTED )); then journalctl -u "$SERVICE" -n 40 --no-pager >&2 || true; fi
  exit "$status"
}
trap cleanup EXIT
trap 'on_error "$?" "$LINENO" "$BASH_COMMAND"' ERR
trap 'on_error 130 "$LINENO" "用户中断"' INT
trap 'on_error 143 "$LINENO" "收到终止信号"' TERM

STAGE='1/5 校验上传包'
log "$STAGE"
[[ "$(id -u)" == 0 ]] || abort '请在服务器 root SSH 终端运行。'
normalized_directory "$PACKAGE_DIR" || abort '上传目录路径无效。'
normalized_directory "$RELEASE_ROOT" || abort '发布目录路径无效。'
normalized_directory "$RECORD_ROOT" || abort '记录目录路径无效。'
[[ "$EXPECTED_SHA" =~ ^[[:xdigit:]]{64}$ ]] || abort 'JAR 摘要格式无效。'
[[ "$WAIT_SECONDS" =~ ^[1-9][0-9]*$ ]] || abort '健康等待秒数无效。'
for command in sha256sum install chown runuser systemctl curl grep readlink mktemp ln mv; do
  command -v "$command" >/dev/null || abort "缺少命令：$command"
done
[[ -d "$PACKAGE_DIR" ]] || abort "上传目录不存在：$PACKAGE_DIR"
[[ -d "$RELEASE_ROOT/releases" && ! -L "$RELEASE_ROOT" ]] || abort '发布目录不存在或类型不符合预期。'
[[ -d "$RECORD_ROOT" && ! -L "$RECORD_ROOT" ]] || abort '记录目录不存在或为符号链接。'
cd "$PACKAGE_DIR"
sha256sum -c SHA256SUMS
verify_jar "$PACKAGE_DIR/$JAR"
[[ -L "$CURRENT" ]] || abort 'current.jar 必须是符号链接。'
if ! mkdir -m 0700 -- "$LOCK" 2>/dev/null; then
  abort "已有更新任务或遗留锁：$LOCK；本次不修改服务。"
fi
LOCK_OWNED=1
OLD=$(readlink -f "$CURRENT")
case "$OLD" in
  "$RELEASE_ROOT/releases/"*.jar) ;;
  *) abort '当前 JAR 不在本项目发布目录中。' ;;
esac
[[ -f "$OLD" ]] || abort "当前 JAR 不存在：$OLD"
runuser -u accountbook -- test -r "$OLD"
if [[ -e "$NEW" || -L "$NEW" ]]; then verify_jar "$NEW"; fi

STAGE='2/5 自动记录旧版本'
log "$STAGE"
RECORD=$(mktemp -d "$RECORD_ROOT/account-book-release-record-$VERSION-XXXXXX")
sha256sum "$OLD" > "$RECORD/previous-jar.sha256"
printf '%s\n' "$OLD" > "$RECORD/previous-jar-path.txt"
log "旧版本：$OLD"
log "记录目录：$RECORD"

STAGE='3/5 安装并切换 JAR'
log "$STAGE"
if [[ ! -e "$NEW" ]]; then
  TMP_JAR=$(mktemp "$RELEASE_ROOT/releases/.$JAR.tmp.XXXXXX")
  install -m 0640 -- "$PACKAGE_DIR/$JAR" "$TMP_JAR"
  chown accountbook:accountbook "$TMP_JAR"
  verify_jar "$TMP_JAR"
  mv -nT -- "$TMP_JAR" "$NEW"
fi
verify_jar "$NEW"
runuser -u accountbook -- test -r "$NEW"
[[ -L "$CURRENT" && "$(readlink -f "$CURRENT")" == "$OLD" ]]
TMP_LINK="$RELEASE_ROOT/.current.jar.update.$$"
[[ ! -e "$TMP_LINK" && ! -L "$TMP_LINK" ]]
ln -s -- "releases/$JAR" "$TMP_LINK"
SWITCH_ATTEMPTED=1
mv -fT -- "$TMP_LINK" "$CURRENT"
TMP_LINK=''
[[ "$(readlink -f "$CURRENT")" == "$NEW" ]]

STAGE='4/5 重启后端服务'
log "$STAGE"
# Restart even if the symlink already names this JAR: the old JVM may still be running.
systemctl restart "$SERVICE"
STAGE='5/5 等待健康检查'
log "${STAGE}（最多约 $WAIT_SECONDS 秒）"
if ! wait_ready; then
  log '新版未在等待期内返回 UP。' >&2
  false
fi
systemctl is-active "$SERVICE"
[[ "$(readlink -f "$CURRENT")" == "$NEW" ]]
verify_jar "$NEW"
log "更新成功：$NEW"
log "$HEALTH"
log '全部旧 JAR 已保留。本脚本未提前停服，也未执行数据库或配置备份。'
