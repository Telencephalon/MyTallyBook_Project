#!/usr/bin/env bash
set -Eeuo pipefail
umask 027

artifact=''
sha_file=''
release_root='/opt/account-book'
health_url='http://127.0.0.1:7631/actuator/health'
keep=3
restart=1
usage() { echo "Usage: sudo $0 --artifact FILE --sha256 FILE [--release-root DIR] [--health-url URL] [--keep N] [--no-restart]" >&2; }
while (($#)); do
  case "$1" in
    --artifact) artifact=${2:?missing artifact}; shift 2 ;;
    --sha256) sha_file=${2:?missing checksum}; shift 2 ;;
    --release-root) release_root=${2:?missing release root}; shift 2 ;;
    --health-url) health_url=${2:?missing health URL}; shift 2 ;;
    --keep) keep=${2:?missing keep count}; shift 2 ;;
    --no-restart) restart=0; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done
[[ $EUID -eq 0 ]] || { echo 'Run this installer with sudo/root; the application must run as accountbook, never as root.' >&2; exit 1; }
[[ -n "$artifact" && -f "$artifact" ]] || { echo 'Artifact is missing.' >&2; exit 1; }
[[ -n "$sha_file" && -f "$sha_file" ]] || { echo 'Checksum file is missing.' >&2; exit 1; }
[[ "$release_root" =~ ^/[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$ ]] || { echo 'Release root must be a normalized absolute directory.' >&2; exit 1; }
[[ "$release_root" != *"/../"* && "$release_root" != */.. && "$release_root" != *"/./"* && "$release_root" != */. ]] || { echo 'Release root must not contain dot segments.' >&2; exit 1; }
[[ "$keep" =~ ^[1-9][0-9]*$ ]] || { echo '--keep must be a positive integer.' >&2; exit 1; }
command -v sha256sum >/dev/null || { echo 'sha256sum is required.' >&2; exit 1; }

artifact_name=$(basename -- "$artifact")
[[ "$artifact_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$ ]] || { echo 'Unsafe artifact name.' >&2; exit 1; }
expected_line=$(awk 'NF { print; exit }' "$sha_file")
expected_hash=$(awk '{print $1}' <<<"$expected_line")
[[ "$expected_hash" =~ ^[[:xdigit:]]{64}$ ]] || { echo 'Checksum file is invalid.' >&2; exit 1; }
printf '%s  %s\n' "$expected_hash" "$artifact_name" | (cd "$(dirname -- "$artifact")" && sha256sum -c -) >/dev/null

release_dir="$release_root/releases"
current_link="$release_root/current.jar"
mkdir -p -- "$release_dir"
if [[ -e "$current_link" && ! -L "$current_link" ]]; then
  echo 'current.jar exists but is not a symlink; refusing to replace it.' >&2
  exit 1
fi
if id accountbook >/dev/null 2>&1; then
  chown accountbook:accountbook "$release_dir" "$release_root" 2>/dev/null || true
fi
tmp_release="$release_dir/.${artifact_name}.tmp.$$"
new_link="$release_root/.current.jar.$$"
previous_target=''
if [[ -L "$current_link" ]]; then previous_target=$(readlink -- "$current_link"); fi
cleanup() { rm -f -- "$tmp_release" "$new_link"; }
trap cleanup EXIT

install -m 0640 -- "$artifact" "$tmp_release"
if id accountbook >/dev/null 2>&1; then chown accountbook:accountbook "$tmp_release"; fi
mv -fT -- "$tmp_release" "$release_dir/$artifact_name"
ln -s -- "releases/$artifact_name" "$new_link"
mv -fT -- "$new_link" "$current_link"

rollback() {
  echo 'Release health check failed; restoring previous current.jar.' >&2
  if [[ -n "$previous_target" ]]; then
    ln -s -- "$previous_target" "$new_link"
    mv -fT -- "$new_link" "$current_link"
    if (( restart )) && command -v systemctl >/dev/null; then systemctl restart account-book.service || true; fi
  else
    rm -f -- "$current_link"
  fi
}
if (( restart )); then
  command -v systemctl >/dev/null || { rollback; echo 'systemctl is required unless --no-restart is used.' >&2; exit 1; }
  systemctl restart account-book.service || { rollback; exit 1; }
  command -v curl >/dev/null || { rollback; echo 'curl is required for health verification.' >&2; exit 1; }
  if ! curl --fail --silent --show-error --max-time 8 -- "$health_url" >/dev/null; then rollback; exit 1; fi
fi

mapfile -t releases < <(find "$release_dir" -maxdepth 1 -type f -name '*.jar' -printf '%T@ %p\n' | sort -nr | awk '{print $2}')
for ((i=keep; i<${#releases[@]}; i++)); do
  [[ "${releases[$i]}" -ef "$current_link" ]] && continue
  rm -f -- "${releases[$i]}"
done
echo "Installed $artifact_name; current.jar now points to releases/$artifact_name."
