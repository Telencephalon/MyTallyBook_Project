#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

defaults_file='/etc/account-book/backup.cnf'
backup_dir='/var/backups/account-book'
database='account_book'
retention_days=7
weekly_keep=4
while (($#)); do
  case "$1" in
    --defaults-extra-file) defaults_file=${2:?missing option file}; shift 2 ;;
    --backup-dir) backup_dir=${2:?missing backup directory}; shift 2 ;;
    --database) database=${2:?missing database}; shift 2 ;;
    --retention-days) retention_days=${2:?missing retention days}; shift 2 ;;
    --weekly-keep) weekly_keep=${2:?missing weekly keep count}; shift 2 ;;
    -h|--help) echo "Usage: sudo $0 [--defaults-extra-file FILE] [--backup-dir DIR] [--database NAME] [--retention-days N]"; exit 0 ;;
    *) echo 'Unknown option.' >&2; exit 2 ;;
  esac
done
[[ $EUID -eq 0 ]] || { echo 'Run backup as root; credentials remain in a root-owned option file.' >&2; exit 1; }
[[ "$database" =~ ^[A-Za-z0-9_]+$ ]] || { echo 'Unsafe database name.' >&2; exit 1; }
[[ "$retention_days" =~ ^[1-9][0-9]*$ ]] || { echo 'Retention must be a positive integer.' >&2; exit 1; }
[[ "$weekly_keep" =~ ^[1-9][0-9]*$ ]] || { echo 'Weekly keep count must be a positive integer.' >&2; exit 1; }
[[ -f "$defaults_file" && ! -L "$defaults_file" ]] || { echo 'Credentials option file is missing or a symlink.' >&2; exit 1; }
owner=$(stat -c '%U' -- "$defaults_file")
mode=$(stat -c '%a' -- "$defaults_file")
[[ "$owner" = root && "$mode" = 600 ]] || { echo 'Credentials option file must be owned by root with mode 600.' >&2; exit 1; }
command -v mysqldump >/dev/null || { echo 'mysqldump is required.' >&2; exit 1; }
command -v gzip >/dev/null || { echo 'gzip is required.' >&2; exit 1; }
command -v sha256sum >/dev/null || { echo 'sha256sum is required.' >&2; exit 1; }
mkdir -p -- "$backup_dir"
chmod 700 -- "$backup_dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
base="$backup_dir/${database}_${stamp}.sql.gz"
tmp="${base}.tmp.$$"
cleanup() { rm -f -- "$tmp"; }
trap cleanup EXIT

mysqldump --defaults-extra-file="$defaults_file" \
  --single-transaction --routines --triggers --events \
  --set-gtid-purged=OFF --no-tablespaces -- "$database" | gzip -n >"$tmp"
gzip -t -- "$tmp"
mv -f -- "$tmp" "$base"
sha256sum -- "$base" >"$base.sha256"
chmod 600 -- "$base" "$base.sha256"

weekly_dir="$backup_dir/weekly"
if [[ "$(date -u +%u)" = 7 ]]; then
  mkdir -p -- "$weekly_dir"
  chmod 700 -- "$weekly_dir"
  weekly_base="$weekly_dir/$(basename -- "$base")"
  cp -- "$base" "$weekly_base"
  sha256sum -- "$weekly_base" >"$weekly_base.sha256"
  chmod 600 -- "$weekly_base" "$weekly_base.sha256"
fi

find "$backup_dir" -maxdepth 1 -type f \( -name "${database}_*.sql.gz" -o -name "${database}_*.sql.gz.sha256" \) -mtime +"$retention_days" -delete
if [[ -d "$weekly_dir" ]]; then
  mapfile -t weekly_files < <(find "$weekly_dir" -maxdepth 1 -type f -name "${database}_*.sql.gz" -printf '%T@ %p\n' | sort -nr | awk '{print $2}')
  for ((i=weekly_keep; i<${#weekly_files[@]}; i++)); do
    rm -f -- "${weekly_files[$i]}" "${weekly_files[$i]}.sha256"
  done
fi
echo "Created logical backup: $base"
echo 'Store backups on encrypted storage or apply the approved host-level encryption policy; gzip is not encryption.'
