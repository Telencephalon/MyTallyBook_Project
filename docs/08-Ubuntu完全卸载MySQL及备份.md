# Ubuntu 完全卸载 MySQL 及备份

> 文档版本：V1.0
>
> 编制日期：2026-08-14
>
> 适用系统：Ubuntu、Debian
>
> 危险级别：高，执行后 MySQL 数据和指定备份无法恢复

## 1. 删除范围

本文脚本会永久删除：

- 名称以 `mysql`、`libmysql`、`default-mysql` 开头的 DEB 软件包。
- `/var/lib/mysql`、`/var/lib/mysql-files`、`/var/lib/mysql-keyring`。
- `/etc/mysql`。
- `/var/log/mysql`、`/var/log/mysqld.log`。
- MySQL systemd 覆盖配置和运行目录。
- Oracle MySQL APT 仓库配置。
- root 用户的 `.my.cnf`、`.mylogin.cnf`。
- `/var/backups/mysql-before-tuning`。
- `/var/backups/mysql-reinstall-*`。
- `/var/backups/mysql-failed-init-*`。
- `/tmp/mysql-apt-config_*.deb`。
- `mysql` 系统用户和用户组。

本文脚本不会执行 `apt autoremove`，避免连带删除其他被系统标记为自动安装的软件。

## 2. 执行前最后确认

执行后不能再从服务器恢复：

```text
account_book 数据库
MySQL 用户和权限
MySQL 配置
此前生成的 SQL/Gzip 备份
失败初始化时移动的数据目录
```

如果这些内容仍有任何保留价值，不要执行本文脚本。

## 3. 一次性完整卸载脚本

复制并完整执行以下代码块：

```bash
sudo bash <<'MYSQL_TOTAL_PURGE'
set -Eeuo pipefail

echo '===== 检查操作系统 ====='
. /etc/os-release

case "${ID:-}" in
  ubuntu|debian)
    printf 'OS: %s\n' "${PRETTY_NAME:-unknown}"
    ;;
  *)
    echo "ERROR: 本脚本只适用于 Ubuntu/Debian，当前系统是 ${ID:-unknown}"
    exit 1
    ;;
esac

echo '===== 收集 MySQL 软件包 ====='
MYSQL_PACKAGES=()
mapfile -t MYSQL_PACKAGES < <(
  dpkg-query -W -f='${binary:Package}\n' 2>/dev/null \
  | grep -E '^(mysql|libmysql|default-mysql)' \
  | sort -u \
  || true
)

if [ "${#MYSQL_PACKAGES[@]}" -gt 0 ]; then
  printf '将永久卸载以下软件包：\n'
  printf '  %s\n' "${MYSQL_PACKAGES[@]}"
else
  echo '没有检测到 MySQL 软件包。'
fi

echo '===== 收集固定清理目标 ====='
MYSQL_STATIC_TARGETS=(
  /var/lib/mysql
  /var/lib/mysql-files
  /var/lib/mysql-keyring
  /etc/mysql
  /var/log/mysql
  /var/log/mysqld.log
  /run/mysqld
  /etc/systemd/system/mysql.service.d
  /etc/systemd/system/mysqld.service.d
  /etc/apt/sources.list.d/mysql.list
  /etc/apt/sources.list.d/mysql.list.save
  /usr/share/keyrings/mysql-apt-config.gpg
  /etc/apt/trusted.gpg.d/mysql.gpg
  /etc/logrotate.d/mysql
  /root/.my.cnf
  /root/.mylogin.cnf
)

MYSQL_DELETE_TARGETS=()

for MYSQL_TARGET in "${MYSQL_STATIC_TARGETS[@]}"; do
  MYSQL_DELETE_TARGETS+=("${MYSQL_TARGET}")
done

echo '===== 收集本项目创建的 MySQL 备份目录 ====='
while IFS= read -r -d '' MYSQL_BACKUP_TARGET; do
  MYSQL_DELETE_TARGETS+=("${MYSQL_BACKUP_TARGET}")
done < <(
  find /var/backups \
    -mindepth 1 \
    -maxdepth 1 \
    \( \
      -name 'mysql-before-tuning' -o \
      -name 'mysql-reinstall-*' -o \
      -name 'mysql-failed-init-*' \
    \) \
    -print0 2>/dev/null
)

echo '===== 收集临时 MySQL APT 配置包 ====='
while IFS= read -r -d '' MYSQL_TMP_TARGET; do
  MYSQL_DELETE_TARGETS+=("${MYSQL_TMP_TARGET}")
done < <(
  find /tmp \
    -mindepth 1 \
    -maxdepth 1 \
    -type f \
    -name 'mysql-apt-config_*.deb' \
    -print0 2>/dev/null
)

echo '===== 校验所有删除路径 ====='
MYSQL_SAFE_TARGETS=()
declare -A MYSQL_SEEN_TARGETS=()

for MYSQL_TARGET in "${MYSQL_DELETE_TARGETS[@]}"; do
  MYSQL_REAL_TARGET="$(readlink -f -- "${MYSQL_TARGET}" 2>/dev/null || true)"

  if [ -z "${MYSQL_REAL_TARGET}" ]; then
    echo "ERROR: 无法解析路径：${MYSQL_TARGET}"
    exit 1
  fi

  case "${MYSQL_REAL_TARGET}" in
    /var/lib/mysql|\
    /var/lib/mysql-files|\
    /var/lib/mysql-keyring|\
    /etc/mysql|\
    /var/log/mysql|\
    /var/log/mysqld.log|\
    /run/mysqld|\
    /etc/systemd/system/mysql.service.d|\
    /etc/systemd/system/mysqld.service.d|\
    /etc/apt/sources.list.d/mysql.list|\
    /etc/apt/sources.list.d/mysql.list.save|\
    /usr/share/keyrings/mysql-apt-config.gpg|\
    /etc/apt/trusted.gpg.d/mysql.gpg|\
    /etc/logrotate.d/mysql|\
    /root/.my.cnf|\
    /root/.mylogin.cnf)
      ;;
    /var/backups/mysql-before-tuning|\
    /var/backups/mysql-reinstall-*|\
    /var/backups/mysql-failed-init-*|\
    /tmp/mysql-apt-config_*.deb)
      ;;
    *)
      echo "ERROR: 路径不在删除白名单中：${MYSQL_REAL_TARGET}"
      exit 1
      ;;
  esac

  if [ -z "${MYSQL_SEEN_TARGETS[${MYSQL_REAL_TARGET}]+x}" ]; then
    MYSQL_SAFE_TARGETS+=("${MYSQL_REAL_TARGET}")
    MYSQL_SEEN_TARGETS["${MYSQL_REAL_TARGET}"]=1
  fi
done

printf '将永久删除以下路径（不存在的路径会跳过）：\n'
printf '  %s\n' "${MYSQL_SAFE_TARGETS[@]}"

echo
echo '警告：MySQL 数据、配置和上述备份将永久删除，无法恢复。'
read -r -p '输入 DELETE MYSQL AND BACKUPS 继续：' MYSQL_DELETE_CONFIRM </dev/tty

if [ "${MYSQL_DELETE_CONFIRM}" != 'DELETE MYSQL AND BACKUPS' ]; then
  echo '确认文本不匹配，操作终止，未删除任何内容。'
  exit 1
fi

echo '===== 停止 MySQL ====='
systemctl stop mysql 2>/dev/null || true
systemctl stop mysqld 2>/dev/null || true

if pgrep -x mysqld >/dev/null 2>&1; then
  echo 'ERROR: mysqld 进程仍在运行，停止清理。'
  exit 1
fi

echo '===== 卸载 MySQL 软件包 ====='
if [ "${#MYSQL_PACKAGES[@]}" -gt 0 ]; then
  apt-get purge -y "${MYSQL_PACKAGES[@]}"
fi

echo '===== 删除经过白名单校验的数据、配置和备份 ====='
for MYSQL_SAFE_TARGET in "${MYSQL_SAFE_TARGETS[@]}"; do
  if [ -e "${MYSQL_SAFE_TARGET}" ] || [ -L "${MYSQL_SAFE_TARGET}" ]; then
    printf 'Deleting: %s\n' "${MYSQL_SAFE_TARGET}"
    rm -rf --one-file-system -- "${MYSQL_SAFE_TARGET}"
  fi
done

echo '===== 删除残留 MySQL systemd 状态 ====='
systemctl daemon-reload
systemctl reset-failed mysql 2>/dev/null || true
systemctl reset-failed mysqld 2>/dev/null || true

echo '===== 删除 mysql 系统用户和用户组 ====='
if getent passwd mysql >/dev/null 2>&1; then
  userdel mysql || echo 'WARNING: mysql 用户删除失败，请查看是否仍有进程或文件占用。'
fi

if getent group mysql >/dev/null 2>&1; then
  groupdel mysql || echo 'WARNING: mysql 用户组删除失败，请检查是否仍被其他账号使用。'
fi

echo '===== 更新 APT 索引 ====='
if ! apt-get update; then
  echo 'WARNING: APT 索引更新失败，但不影响此前已经完成的 MySQL 清理。'
fi

echo '===== 验证清理结果 ====='
echo '-- Remaining packages --'
dpkg-query -W -f='${binary:Package}\t${Version}\n' 2>/dev/null \
  | grep -E '^(mysql|libmysql|default-mysql)' \
  || true

echo '-- Remaining commands --'
command -v mysql || true
command -v mysqld || true

echo '-- Remaining service units --'
systemctl list-unit-files 2>/dev/null \
  | grep -E '^(mysql|mysqld)\.service' \
  || true

echo '-- Remaining project backup directories --'
find /var/backups \
  -mindepth 1 \
  -maxdepth 1 \
  \( \
    -name 'mysql-before-tuning' -o \
    -name 'mysql-reinstall-*' -o \
    -name 'mysql-failed-init-*' \
  \) \
  -print 2>/dev/null \
  || true

echo 'MySQL 软件包、数据、配置和本项目 MySQL 备份清理完成。'
echo '未执行 apt autoremove。'
MYSQL_TOTAL_PURGE
```

## 4. 验证标准

脚本最后四项检查应没有有效输出：

```text
Remaining packages
Remaining commands
Remaining service units
Remaining project backup directories
```

如果仍有软件包输出，不要手工删除 `/var/lib/dpkg` 中的文件。先记录包名，再执行：

```bash
sudo apt-get purge 软件包名称
```

## 5. 明确不会自动删除的内容

为避免扩大删除范围，脚本不会处理：

- 其他 Linux 用户主目录中的 `.my.cnf` 或 `.mylogin.cnf`。
- 项目仓库中的数据库脚本和 Markdown 文档。
- 名称不符合本文三种规则的自定义备份目录。
- 京东云对象存储、云硬盘快照或其他服务器上的备份。
- 不以 `mysql`、`libmysql`、`default-mysql` 开头的第三方数据库驱动包。
- `apt autoremove` 建议删除的其他依赖。

这些内容如果也要删除，必须先单独确认准确位置和用途。

## 6. 官方参考

- [MySQL APT 安装与卸载](https://dev.mysql.com/doc/refman/8.4/en/linux-installation-apt-repo.html)
- [MySQL 数据目录初始化](https://dev.mysql.com/doc/refman/8.4/en/data-directory-initialization.html)
