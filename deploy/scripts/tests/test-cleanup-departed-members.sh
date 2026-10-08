#!/usr/bin/env bash
set -Eeuo pipefail

# This suite verifies the real launcher's routing and rejection behavior. The
# mysql boundary is faked, so it does not claim to validate SQL or a database.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
SCRIPT="$ROOT/deploy/scripts/cleanup-departed-members.sh"
mkdir -p "$ROOT/account-book-server/target"
SUITE=$(mktemp -d "$ROOT/account-book-server/target/cleanup-member-tests-XXXXXX")
case "$(readlink -f "$SUITE")" in
  "$ROOT/account-book-server/target/cleanup-member-tests-"*) ;;
  *) printf 'Unsafe test directory: %s\n' "$SUITE" >&2; exit 1 ;;
esac
trap 'rm -rf -- "$SUITE"' EXIT

COUNT=0
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { COUNT=$((COUNT + 1)); printf 'PASS: %s\n' "$*"; }
fixture() {
  CASE="$SUITE/$1"
  mkdir -p "$CASE/bin with spaces" "$CASE/state"
  MYSQL="$CASE/bin with spaces/mysql"
  cat > "$MYSQL" <<'FAKE'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$@" > "$FAKE_MYSQL_STATE/arguments"
cat > "$FAKE_MYSQL_STATE/input.sql"
printf 'called\n' >> "$FAKE_MYSQL_STATE/calls"
if [[ "${FAKE_MYSQL_EXIT:-0}" != 0 ]]; then
  printf 'ERROR 1049 (42000): Unknown database\n' >&2
  exit "$FAKE_MYSQL_EXIT"
fi
printf 'fake mysql completed; SQL was not executed\n'
FAKE
  chmod +x "$MYSQL"
}

run_cleanup() {
  FAKE_MYSQL_STATE="$CASE/state" FAKE_MYSQL_EXIT="${MYSQL_EXIT:-0}" \
    bash "$SCRIPT" --mysql "$MYSQL" "$@" > "$CASE/output" 2>&1
}
expect_success() {
  if ! run_cleanup "$@"; then cat "$CASE/output" >&2; fail 'launcher should succeed'; fi
  [[ -f "$CASE/state/calls" ]] || fail 'expected mysql invocation is missing'
  [[ $(wc -l < "$CASE/state/calls") -eq 1 ]] || fail 'launcher must use one mysql session'
}
expect_rejection() {
  if run_cleanup "$@"; then cat "$CASE/output" >&2; fail 'unsafe or incomplete arguments were accepted'; fi
  [[ ! -f "$CASE/state/calls" ]] || fail 'rejected arguments must not start mysql'
}
assert_assignment() {
  local name=$1 expected=$2
  grep -Eq "(SET[[:space:]]+|,[[:space:]]*)@$name[[:space:]]*=[[:space:]]*$expected[[:space:]]*[,;]" \
    "$CASE/state/input.sql" || fail "SQL session variable @$name must be $expected"
}
assert_argument() {
  local name=$1 expected=$2 found=0
  local -a args
  mapfile -t args < "$CASE/state/arguments"
  for ((i=0; i<${#args[@]}; i++)); do
    if [[ "${args[$i]}" == "$name=$expected" ]]; then found=1; break; fi
    if [[ "${args[$i]}" == "$name" && "${args[$((i+1))]:-}" == "$expected" ]]; then found=1; break; fi
  done
  [[ "$found" == 1 ]] || fail "mysql argument $name must carry [$expected] as a single value"
}
assert_safe_mysql_flags() {
  local flag
  for flag in --batch --raw --show-warnings --password; do
    grep -Fxq -- "$flag" "$CASE/state/arguments" || fail "required mysql flag missing: $flag"
  done
  if grep -Eq -- '^--force($|=)|^-f$|^--password=|^-p.+' "$CASE/state/arguments"; then
    fail 'mysql invocation must abort SQL errors and never carry a password value'
  fi
  [[ $(grep -Fxc -- --password "$CASE/state/arguments") -eq 1 ]] || fail 'password prompt flag must occur exactly once'
}

# Detects accidental default deletion, multiple SQL connections that lose
# session variables/transactions, unsafe password values, or --force routing.
fixture default_preview
expect_success
assert_assignment cleanup_apply 0
assert_assignment cleanup_expected_database "'account_book_dev'"
assert_assignment cleanup_ledger_id 1
assert_assignment cleanup_user_id NULL
assert_argument --database account_book_dev
assert_argument --user root
assert_argument --socket /opt/mysql8/run/mysql.sock
assert_safe_mysql_flags
grep -Eq 'DELETE[[:space:]]+lm|DELETE[[:space:]]+FROM[[:space:]]+ledger_member' \
  "$CASE/state/input.sql" || fail 'SQL body must be sent through the same stdin session'
pass 'default invocation previews in one session with safe mysql arguments'

fixture explicit_preview
expect_success --mode preview
assert_assignment cleanup_apply 0
pass 'explicit preview preserves the default non-apply behavior'

fixture explicit_apply
expect_success --mode apply
assert_assignment cleanup_apply 1
assert_assignment cleanup_user_id NULL
assert_safe_mysql_flags
pass 'only explicit apply enables deletion for all departed associations in the target ledger'

fixture user_scope
expect_success --mode apply --user-id 42
assert_assignment cleanup_apply 1
assert_assignment cleanup_user_id 42
pass 'optional user scope is passed as a numeric session value'

fixture largest_user_id
expect_success --user-id 18446744073709551615
assert_assignment cleanup_apply 0
assert_assignment cleanup_user_id 18446744073709551615
pass 'maximum unsigned BIGINT user ID is preserved without shell integer overflow'

fixture alternate_connection
expect_success --database account_book_fixture --ledger-id 1 \
  --mysql-user cleanup_ops --socket '/tmp/mysql fixture.sock'
assert_argument --database account_book_fixture
assert_argument --user cleanup_ops
assert_argument --socket '/tmp/mysql fixture.sock'
assert_assignment cleanup_expected_database "'account_book_fixture'"
assert_assignment cleanup_ledger_id 1
assert_safe_mysql_flags
pass 'explicit connection values remain single arguments and database guard matches'

fixture database_failure
MYSQL_EXIT=72
if run_cleanup; then fail 'database/client failure must return nonzero'; fi
[[ -f "$CASE/state/calls" ]] || fail 'database failure fixture did not invoke mysql'
grep -Fq 'Unknown database' "$CASE/output" || fail 'database error must remain visible'
MYSQL_EXIT=0
pass 'mysql error propagates as failure with its diagnostic output'

REJECTION=0
reject_case() {
  local description=$1
  shift
  REJECTION=$((REJECTION + 1))
  fixture "reject-$REJECTION"
  expect_rejection "$@"
  pass "$description"
}

# Each case would fail if the wrapper accepted SQL fragments, extra mysql
# flags, unsupported target ledgers, or silently substituted missing values.
reject_case 'unsupported mode is rejected before mysql' --mode delete
reject_case 'database SQL fragment is rejected before mysql' --database 'account_book_dev; DROP TABLE app_user;'
reject_case 'database quote is rejected before mysql' --database "account_book_dev'"
reject_case 'database option injection is rejected before mysql' --database --force
reject_case 'database newline is rejected before mysql' --database $'account_book_dev\nCOMMIT'
reject_case 'empty database is rejected before mysql' --database ''
reject_case 'another ledger is rejected before mysql' --ledger-id 2
reject_case 'zero ledger is rejected before mysql' --ledger-id 0
reject_case 'negative ledger is rejected before mysql' --ledger-id -1
reject_case 'ledger SQL fragment is rejected before mysql' --ledger-id '1;COMMIT'
reject_case 'zero user ID is rejected before mysql' --user-id 0
reject_case 'negative user ID is rejected before mysql' --user-id -1
reject_case 'leading-zero user ID is rejected before mysql' --user-id 01
reject_case 'fractional user ID is rejected before mysql' --user-id 1.5
reject_case 'exponent user ID is rejected before mysql' --user-id 1e3
reject_case 'user ID SQL fragment is rejected before mysql' --user-id '1;DELETE FROM ledger_member'
reject_case 'explicit SQL NULL cannot broaden a selected user scope' --mode apply --user-id NULL
reject_case 'unsigned BIGINT overflow is rejected before mysql' --user-id 18446744073709551616
reject_case 'oversized user ID is rejected before mysql' --user-id 999999999999999999999
reject_case 'mysql user SQL fragment is rejected before mysql' --mysql-user 'root;COMMIT'
reject_case 'mysql user option injection is rejected before mysql' --mysql-user --force
reject_case 'unknown option is rejected before mysql' --force
reject_case 'password value option is rejected before mysql' --password do-not-pass-passwords
for option in --mode --database --ledger-id --user-id --mysql-user --socket --mysql; do
  reject_case "$option without a value is rejected before mysql" "$option"
done

printf 'All %s cleanup launcher behavior tests passed; no SQL or live database was executed.\n' "$COUNT"
