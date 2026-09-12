#!/usr/bin/env bash
set -Eeuo pipefail

# Ubuntu package owns /usr/sbin/nginx; /opt/nginx is the project-managed root.
DOMAIN="www.cr-chenny.com"
SERVER_IP="117.72.101.42"
BACKEND="127.0.0.1:7631"
NGINX_ROOT="/opt/nginx"
MODE="local"
TEMPLATE_DIR_OVERRIDE=""
APPLY=0
CONFIRM=""

usage() {
  cat <<'EOF'
Usage: install-nginx-prod.sh [options]
Default is dry-run. Changes require --apply --confirm NGINX-INSTALL.
  --apply                    perform installation/configuration
  --confirm TEXT             required value: NGINX-INSTALL
  --domain NAME              API host (default: www.cr-chenny.com)
  --server-ip ADDRESS        expected server address (default: 117.72.101.42)
  --backend HOST:PORT        loopback backend (default: 127.0.0.1:7631)
  --nginx-root PATH          managed root (default: /opt/nginx)
  --template-dir PATH        template directory when uploaded standalone
  --mode local|https         loopback 8081 or HTTPS 443 (default: local)
EOF
}

log() { printf '[nginx] %s\n' "$*"; }
ROLLBACK_READY=0
fail() {
  printf '[nginx] ERROR: %s\n' "$*" >&2
  if (( ROLLBACK_READY )); then rollback 1; else exit 1; fi
}

while (($#)); do
  case "$1" in
    --apply) APPLY=1; shift ;;
    --confirm) [[ $# -ge 2 ]] || fail '--confirm requires a value'; CONFIRM="$2"; shift 2 ;;
    --domain) [[ $# -ge 2 ]] || fail '--domain requires a value'; DOMAIN="$2"; shift 2 ;;
    --server-ip) [[ $# -ge 2 ]] || fail '--server-ip requires a value'; SERVER_IP="$2"; shift 2 ;;
    --backend) [[ $# -ge 2 ]] || fail '--backend requires a value'; BACKEND="$2"; shift 2 ;;
    --nginx-root) [[ $# -ge 2 ]] || fail '--nginx-root requires a value'; NGINX_ROOT="$2"; shift 2 ;;
    --template-dir) [[ $# -ge 2 ]] || fail '--template-dir requires a value'; TEMPLATE_DIR_OVERRIDE="$2"; shift 2 ;;
    --mode) [[ $# -ge 2 ]] || fail '--mode requires a value'; MODE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1" ;;
  esac
done

[[ "$DOMAIN" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || fail "invalid domain: $DOMAIN"
[[ "$DOMAIN" != *..* && "$DOMAIN" != .* && "$DOMAIN" != *. ]] || fail "invalid domain: $DOMAIN"
[[ "$SERVER_IP" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || fail "invalid IPv4 address: $SERVER_IP"
IFS=. read -r o1 o2 o3 o4 <<<"$SERVER_IP"
for octet in "$o1" "$o2" "$o3" "$o4"; do ((octet <= 255)) || fail "invalid IPv4 address: $SERVER_IP"; done
[[ "$BACKEND" =~ ^127\.0\.0\.1:[0-9]{1,5}$ ]] || fail "backend must be loopback host:port: $BACKEND"
backend_port="${BACKEND##*:}"
((backend_port >= 1 && backend_port <= 65535)) || fail "invalid backend port: $BACKEND"
[[ "$NGINX_ROOT" = /* && "$NGINX_ROOT" != *$'\n'* && "$NGINX_ROOT" != *$'\r'* ]] || fail "nginx root must be absolute"
[[ "$NGINX_ROOT" != *..* ]] || fail "nginx root may not contain dot segments"
[[ "$NGINX_ROOT" != "/" ]] || fail "nginx root may not be filesystem root"
if [[ -n "$TEMPLATE_DIR_OVERRIDE" ]]; then
  [[ "$TEMPLATE_DIR_OVERRIDE" = /* && "$TEMPLATE_DIR_OVERRIDE" != *$'\n'* && "$TEMPLATE_DIR_OVERRIDE" != *$'\r'* ]] || fail 'template directory must be absolute'
  [[ "$TEMPLATE_DIR_OVERRIDE" != *..* ]] || fail 'template directory may not contain dot segments'
fi
case "$MODE" in local|https) ;; *) fail 'mode must be local or https' ;; esac

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
if [[ -n "$TEMPLATE_DIR_OVERRIDE" ]]; then TEMPLATE_DIR="$TEMPLATE_DIR_OVERRIDE"; else TEMPLATE_DIR="$REPO_ROOT/deploy/nginx"; fi
HTTP_TEMPLATE="$TEMPLATE_DIR/account-book-http.conf.template"
HTTPS_TEMPLATE="$TEMPLATE_DIR/account-book-https.conf.template"
PROXY_TEMPLATE="$TEMPLATE_DIR/account-book-proxy.inc"
[[ -f "$HTTP_TEMPLATE" && -f "$HTTPS_TEMPLATE" && -f "$PROXY_TEMPLATE" ]] || fail 'Nginx templates are missing'

if (( ! APPLY )); then
  log "DRY-RUN: Ubuntu 22.04 Nginx setup for $DOMAIN ($SERVER_IP)"
  log "would install nginx, create $NGINX_ROOT, render $MODE config and validate nginx -t"
  log "would proxy loopback backend $BACKEND; port 80 is never claimed"
  log 'no network, package, file, firewall or service change was made'
  exit 0
fi

[[ "$CONFIRM" == 'NGINX-INSTALL' ]] || fail 'apply mode requires --confirm NGINX-INSTALL'
(( EUID == 0 )) || fail '--apply must run as root'
[[ -r /etc/os-release ]] || fail '/etc/os-release is unavailable'
. /etc/os-release
[[ "${ID:-}" == ubuntu && "${VERSION_ID:-}" == 22.04 ]] || fail "Ubuntu 22.04 required (found ${ID:-unknown} ${VERSION_ID:-unknown})"

NGINX_ETC="/etc/nginx"
SITE_AVAILABLE="$NGINX_ETC/sites-available/account-book.conf"
SITE_ENABLED="$NGINX_ETC/sites-enabled/account-book.conf"
SNIPPET="$NGINX_ETC/snippets/account-book-proxy.inc"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="$NGINX_ROOT/backups/$STAMP"
TMP_SITE="$(mktemp /tmp/account-book-nginx.XXXXXX)"
TMP_SNIPPET="$(mktemp /tmp/account-book-proxy.XXXXXX)"
POLICY_TMP=""
POLICY_CREATED=0
POLICY_BACKED_UP=0
OLD_SITE=0
OLD_SNIPPET=0
OLD_LINK=0
DEFAULT_MOVED=0
NGINX_WAS_ACTIVE=0
NGINX_STARTED=0

cleanup() {
  rm -f -- "$TMP_SITE" "$TMP_SNIPPET" "$POLICY_TMP"
  if (( POLICY_BACKED_UP )); then
    mv -f -- "$BACKUP_DIR/policy-rc.d" /usr/sbin/policy-rc.d
  elif (( POLICY_CREATED )); then
    rm -f -- /usr/sbin/policy-rc.d
  fi
}
rollback() {
  local rc
  if (( $# > 0 )); then rc="$1"; else rc=$?; fi
  if (( rc != 0 )); then
    log 'configuration failed; restoring this run backup'
    if (( OLD_SITE )); then cp -a -- "$BACKUP_DIR/account-book.conf" "$SITE_AVAILABLE"; else rm -f -- "$SITE_AVAILABLE"; fi
    if (( OLD_SNIPPET )); then cp -a -- "$BACKUP_DIR/account-book-proxy.inc" "$SNIPPET"; else rm -f -- "$SNIPPET"; fi
    if (( OLD_LINK )); then ln -sfn -- "$(cat "$BACKUP_DIR/enabled-target")" "$SITE_ENABLED"; else rm -f -- "$SITE_ENABLED"; fi
    if (( DEFAULT_MOVED )); then mv -f -- "$BACKUP_DIR/default-site" "$NGINX_ETC/sites-enabled/default"; fi
    if nginx -t >/dev/null 2>&1; then
      if (( NGINX_WAS_ACTIVE )); then systemctl reload nginx >/dev/null 2>&1 || true
      elif (( NGINX_STARTED )); then systemctl stop nginx >/dev/null 2>&1 || true
      fi
    fi
  fi
  exit "$rc"
}
trap cleanup EXIT

install -d -m 0755 "$NGINX_ROOT" "$NGINX_ROOT/templates" "$BACKUP_DIR"
cp -f -- "$HTTP_TEMPLATE" "$HTTPS_TEMPLATE" "$PROXY_TEMPLATE" "$NGINX_ROOT/templates/"
if [[ -e "$SITE_AVAILABLE" && ! -f "$SITE_AVAILABLE" && ! -L "$SITE_AVAILABLE" ]]; then fail "$SITE_AVAILABLE must be a regular file or absent"; fi
if [[ -e "$SNIPPET" && ! -f "$SNIPPET" && ! -L "$SNIPPET" ]]; then fail "$SNIPPET must be a regular file or absent"; fi
if [[ -e "$SITE_AVAILABLE" || -L "$SITE_AVAILABLE" ]]; then OLD_SITE=1; cp -a -- "$SITE_AVAILABLE" "$BACKUP_DIR/account-book.conf"; fi
if [[ -e "$SNIPPET" || -L "$SNIPPET" ]]; then OLD_SNIPPET=1; cp -a -- "$SNIPPET" "$BACKUP_DIR/account-book-proxy.inc"; fi
if [[ -L "$SITE_ENABLED" ]]; then OLD_LINK=1; readlink "$SITE_ENABLED" >"$BACKUP_DIR/enabled-target"; fi
if [[ -e "$SITE_ENABLED" && ! -L "$SITE_ENABLED" ]]; then fail "$SITE_ENABLED must be a symlink or absent"; fi
if systemctl is-active --quiet nginx 2>/dev/null; then NGINX_WAS_ACTIVE=1; fi
ROLLBACK_READY=1
trap rollback ERR

# Block package post-install auto-start while the existing public port 80 is
# owned by another application. This policy is removed before returning.
if [[ -L /usr/sbin/policy-rc.d || ( -e /usr/sbin/policy-rc.d && ! -f /usr/sbin/policy-rc.d ) ]]; then
  fail '/usr/sbin/policy-rc.d must be a regular file or absent'
fi
if [[ -e /usr/sbin/policy-rc.d ]]; then
  cp -a -- /usr/sbin/policy-rc.d "$BACKUP_DIR/policy-rc.d"
  POLICY_BACKED_UP=1
fi
POLICY_TMP="$(mktemp /tmp/account-book-policy.XXXXXX)"
cat > "$POLICY_TMP" <<'EOF'
#!/bin/sh
exit 101
EOF
chmod 0755 "$POLICY_TMP"
install -m 0755 "$POLICY_TMP" /usr/sbin/policy-rc.d
rm -f -- "$POLICY_TMP"
POLICY_TMP=""
POLICY_CREATED=1
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends nginx ca-certificates curl
if (( POLICY_CREATED )); then rm -f /usr/sbin/policy-rc.d; POLICY_CREATED=0; fi

install -d -m 0755 "$NGINX_ETC/snippets" "$NGINX_ETC/sites-available" "$NGINX_ETC/sites-enabled"
sed "s#127\\.0\\.0\\.1:7631#$BACKEND#g" "$PROXY_TEMPLATE" >"$TMP_SNIPPET"
install -m 0644 -- "$TMP_SNIPPET" "$SNIPPET"
if [[ "$MODE" == https ]]; then
  cert_dir="/etc/letsencrypt/live/$DOMAIN"
  [[ -s "$cert_dir/fullchain.pem" && -s "$cert_dir/privkey.pem" ]] || fail "trusted certificate not found under $cert_dir"
  sed "s/__API_DOMAIN__/$DOMAIN/g; s#127\.0\.0\.1:7631#$BACKEND#g" "$HTTPS_TEMPLATE" >"$TMP_SITE"
else
  sed "s#127\.0\.0\.1:7631#$BACKEND#g" "$HTTP_TEMPLATE" >"$TMP_SITE"
fi
install -m 0644 -- "$TMP_SITE" "$SITE_AVAILABLE"

# Disable only the distro default site; retain it in the per-run backup.
if [[ -e "$NGINX_ETC/sites-enabled/default" || -L "$NGINX_ETC/sites-enabled/default" ]]; then
  if ! grep -qE 'root[[:space:]]+/var/www/html|Welcome to nginx' "$NGINX_ETC/sites-enabled/default"; then
    fail 'non-default sites-enabled/default detected; inspect it manually before proceeding'
  fi
  mv -- "$NGINX_ETC/sites-enabled/default" "$BACKUP_DIR/default-site"
  DEFAULT_MOVED=1
fi
ln -sfn -- "$SITE_AVAILABLE" "$SITE_ENABLED"

nginx -t
systemctl daemon-reload
systemctl enable --now nginx
NGINX_STARTED=1
systemctl is-active --quiet nginx
systemctl reload nginx
if [[ "$MODE" == https ]]; then
  curl --resolve "$DOMAIN:443:127.0.0.1" --fail --silent --show-error --connect-timeout 5 --max-time 10 "https://$DOMAIN/nginx-health" >/dev/null
else
  curl --fail --silent --show-error --connect-timeout 5 --max-time 10 http://127.0.0.1:8081/nginx-health >/dev/null
fi
cat >"$NGINX_ROOT/INSTALL-MARKER" <<EOF
domain=$DOMAIN
server_ip=$SERVER_IP
backend=$BACKEND
mode=$MODE
installed_at_utc=$STAMP
EOF
chmod 0644 "$NGINX_ROOT/INSTALL-MARKER"
log "Nginx $MODE configuration installed and health check passed"
