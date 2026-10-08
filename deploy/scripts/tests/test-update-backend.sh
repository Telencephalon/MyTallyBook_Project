#!/usr/bin/env bash
set -Eeuo pipefail

# Git for Windows otherwise implements ln -s by copying the target. MSYS
# symlink files provide real -L/readlink/mv semantics without Windows elevation.
case "$(uname -s)" in
  MINGW*|MSYS*)
    if [[ "${MSYS:-}" != winsymlinks ]]; then
      export MSYS=winsymlinks
      exec bash "$0" "$@"
    fi
    ;;
esac

# Destructive service operations are stubbed. All file operations stay in target/.
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
SCRIPT="$ROOT/deploy/scripts/update-backend.sh"
VERSION=20261008-101349
JAR="account-book-server-$VERSION.jar"
mkdir -p "$ROOT/account-book-server/target"
SUITE=$(mktemp -d "$ROOT/account-book-server/target/update-backend-tests-XXXXXX")
case "$(readlink -f "$SUITE")" in
  "$ROOT/account-book-server/target/update-backend-tests-"*) ;;
  *) printf 'Unsafe test directory: %s\n' "$SUITE" >&2; exit 1 ;;
esac
trap 'rm -rf -- "$SUITE"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_equal() { [[ "$1" == "$2" ]] || fail "$3: expected [$2], got [$1]"; }

fixture() {
  CASE="$SUITE/$1"
  MODE=healthy
  WAIT_SECONDS=3
  mkdir -p "$CASE/bin" "$CASE/package" "$CASE/releases/releases" "$CASE/records" "$CASE/state"
  printf 'old deployed artifact\n' > "$CASE/releases/releases/old.jar"
  ln -s releases/old.jar "$CASE/releases/current.jar"
  [[ -L "$CASE/releases/current.jar" ]] || fail 'test environment must create real Bash-recognized symlinks'
  printf 'new verified artifact\n' > "$CASE/package/$JAR"
  EXPECTED=$(sha256sum "$CASE/package/$JAR" | cut -d ' ' -f 1)
  printf '%s  %s\n' "$EXPECTED" "$JAR" > "$CASE/package/SHA256SUMS"
  for command in id chown runuser systemctl curl journalctl; do
    cat > "$CASE/bin/$command" <<'STUB'
#!/usr/bin/env bash
set -eu
command=${0##*/}
printf '%s %s\n' "$command" "$*" >> "$MOCK_STATE/events"
case "$command" in
  id) printf '0\n' ;;
  chown) : ;;
  runuser) while [[ "$1" != '--' ]]; do shift; done; shift; exec "$@" ;;
  systemctl)
    case "$1" in
      restart)
        current=$(readlink -f "$ACCOUNT_BOOK_RELEASE_ROOT/current.jar")
        printf '%s\n' "$current" > "$MOCK_STATE/running-jar"
        if [[ "$MOCK_MODE" == restart_failure && "${current##*/}" == account-book-server-* ]]; then exit 1; fi
        if [[ "$MOCK_MODE" == rollback_failure && "${current##*/}" == old.jar ]]; then exit 1; fi
        ;;
      is-active) printf 'active\n' ;;
      reset-failed) : ;;
      *) printf 'unexpected systemctl operation: %s\n' "$*" >&2; exit 99 ;;
    esac
    ;;
  curl)
    current=$(readlink -f "$ACCOUNT_BOOK_RELEASE_ROOT/current.jar")
    if [[ "${current##*/}" == account-book-server-* ]]; then
      case "$MOCK_MODE" in
        down|rollback_failure) printf '{"status":"DOWN"}\n'; exit 0 ;;
        nested_up) printf '{"status":"DOWN","components":{"database":{"status":"UP"}}}\n'; exit 0 ;;
        delayed)
          if [[ ! -f "$MOCK_STATE/delayed-once" ]]; then touch "$MOCK_STATE/delayed-once"; exit 7; fi
          ;;
      esac
    fi
    printf '{"status":"UP"}\n'
    ;;
  journalctl) printf 'mock journal\n' ;;
esac
STUB
    chmod +x "$CASE/bin/$command"
  done
}

run_update() {
  PATH="$CASE/bin:$PATH" MOCK_STATE="$CASE/state" MOCK_MODE="$MODE" \
    ACCOUNT_BOOK_PACKAGE_DIR="$CASE/package" \
    ACCOUNT_BOOK_RELEASE_ROOT="$CASE/releases" \
    ACCOUNT_BOOK_RECORD_ROOT="$CASE/records" \
    ACCOUNT_BOOK_EXPECTED_SHA256="$EXPECTED" \
    ACCOUNT_BOOK_WAIT_SECONDS="$WAIT_SECONDS" \
    bash "$SCRIPT" > "$CASE/output" 2>&1
}

expect_success() {
 if ! run_update; then
  cat "$CASE/output" >&2
  fail "$1 must succeed"
 fi
}
expect_failure() {
 if run_update; then cat "$CASE/output" >&2; fail "$1 must fail"; fi
}
assert_previous_current() {
  assert_equal "$(readlink -f "$CASE/releases/current.jar")" "$CASE/releases/releases/old.jar" 'previous release is current'
  assert_equal "$(cat "$CASE/releases/releases/old.jar")" 'old deployed artifact' 'previous release content retained'
}
assert_no_restart() {
  if [[ -f "$CASE/state/events" ]] && grep -q '^systemctl restart ' "$CASE/state/events"; then
    fail 'preflight rejection must not restart the service'
  fi
}
assert_lock_released() { [[ ! -d "$CASE/releases/.update-backend.lock" ]] || fail 'owned lock was left behind'; }
pass() { printf 'PASS: %s\n' "$*"; }

# Detects dependence on missing old static records, deletion of prior releases,
# and switching the symlink without loading the new artifact into the service.
fixture success
printf 'another historical artifact\n' > "$CASE/releases/releases/archive.jar"
expect_success 'verified release without prerequisite record'
assert_equal "$(readlink -f "$CASE/releases/current.jar")" "$CASE/releases/releases/$JAR" 'new release is current'
assert_equal "$(cat "$CASE/state/running-jar")" "$CASE/releases/releases/$JAR" 'new release loaded by service'
[[ -f "$CASE/releases/releases/old.jar" && -f "$CASE/releases/releases/archive.jar" ]] || fail 'all old JARs must remain available'
RECORD=$(find "$CASE/records" -name previous-jar-path.txt)
assert_equal "$(cat "$RECORD")" "$CASE/releases/releases/old.jar" 'rollback records actual old release'
sha256sum -c "${RECORD%/*}/previous-jar.sha256" > /dev/null || fail 'rollback digest is invalid'
assert_lock_released
pass 'verified release installs without prerequisite records and preserves all old JARs'

# Detects changing service state before verifying the fixed release checksum.
fixture bad_checksum
printf 'modified after upload\n' >> "$CASE/package/$JAR"
expect_failure 'modified upload'
assert_previous_current
assert_no_restart
[[ ! -e "$CASE/releases/releases/$JAR" ]] || fail 'invalid upload was installed'
assert_lock_released
pass 'invalid uploaded checksum is rejected before any service mutation'

# Detects a single immediate curl check that treats normal startup as a failure.
fixture delayed
MODE=delayed
WAIT_SECONDS=8
expect_success 'delayed healthy startup'
assert_equal "$(readlink -f "$CASE/releases/current.jar")" "$CASE/releases/releases/$JAR" 'delayed new release stays current'
[[ -f "$CASE/state/delayed-once" ]] || fail 'delayed health fixture did not exercise a transient failure'
assert_lock_released
pass 'transient health failure is retried until startup is ready'

# Detects rollback omitted when restart itself fails, before health polling.
fixture restart_failure
MODE=restart_failure
expect_failure 'new service restart failure'
assert_previous_current
assert_equal "$(cat "$CASE/state/running-jar")" "$CASE/releases/releases/old.jar" 'previous service restarted after failure'
[[ $(grep -c '^systemctl restart account-book.service$' "$CASE/state/events") -eq 2 ]] || fail 'rollback did not restart the previous artifact exactly once'
assert_lock_released
pass 'failed restart restores and starts the previous JAR, returning failure'

# Detects a timed-out unhealthy deployment that returns success or leaves the
# failed new artifact active rather than rolling back.
fixture unhealthy
MODE=down
expect_failure 'unhealthy new deployment'
assert_previous_current
assert_equal "$(cat "$CASE/state/running-jar")" "$CASE/releases/releases/old.jar" 'previous release runs after health failure'
assert_lock_released
pass 'unhealthy deployment times out and restores the previous service'

# Detects matching a nested UP field instead of the top-level service status.
fixture nested_up
MODE=nested_up
expect_failure 'DOWN response containing nested UP'
assert_previous_current
assert_lock_released
pass 'nested UP cannot hide a top-level DOWN status'

# Detects falsely claiming successful recovery when the old service also fails.
fixture rollback_failure
MODE=rollback_failure
expect_failure 'new health failure followed by rollback restart failure'
assert_previous_current
[[ $(grep -c '^systemctl restart account-book.service$' "$CASE/state/events") -eq 2 ]] || fail 'rollback was not attempted once'
grep -Eq '回退.*失败|回退未恢复健康|rollback.*fail|恢复.*失败' "$CASE/output" || fail 'output must truthfully identify recovery failure'
assert_lock_released
pass 'failed recovery is reported and exits nonzero'

# Detects an interrupted previous deployment where only the symlink changed.
# Every retry must restart even when the desired link is already present.
fixture repeated
expect_success 'first deployment'
expect_success 'repeated deployment'
[[ $(grep -c '^systemctl restart account-book.service$' "$CASE/state/events") -eq 2 ]] || fail 'repeated invocation must restart to ensure the package is loaded'
[[ -f "$CASE/releases/releases/old.jar" ]] || fail 'repeat removed the previous JAR'
assert_lock_released
pass 'repeated invocation verifies and restarts the same release without deleting history'

# Detects refusing a byte-identical target left by a failed previous run.
fixture existing_identical
cp "$CASE/package/$JAR" "$CASE/releases/releases/$JAR"
expect_success 'matching version left by previous attempt'
assert_equal "$(readlink -f "$CASE/releases/current.jar")" "$CASE/releases/releases/$JAR" 'matching prior target reused'
assert_lock_released
pass 'matching target from a previous attempt is safely reused'

# Detects overwriting an existing immutable release with different contents.
fixture existing_different
printf 'different immutable artifact\n' > "$CASE/releases/releases/$JAR"
expect_failure 'same version with different content'
assert_previous_current
assert_no_restart
assert_equal "$(cat "$CASE/releases/releases/$JAR")" 'different immutable artifact' 'conflicting version was not overwritten'
assert_lock_released
pass 'conflicting version is rejected without overwriting or restarting'

# Detects following a preexisting version symlink while assigning a release.
fixture existing_symlink
ln -s old.jar "$CASE/releases/releases/$JAR"
expect_failure 'existing release path is a symlink'
assert_previous_current
assert_no_restart
[[ -L "$CASE/releases/releases/$JAR" ]] || fail 'preexisting symlink was changed'
assert_lock_released
pass 'existing version symlink is rejected before mutation'

# Detects two updaters racing or cleaning up a lock they do not own.
fixture locked
mkdir "$CASE/releases/.update-backend.lock"
printf 'other updater\n' > "$CASE/releases/.update-backend.lock/owner-marker"
expect_failure 'concurrent update'
assert_previous_current
assert_no_restart
[[ -f "$CASE/releases/.update-backend.lock/owner-marker" ]] || fail 'another updater lock was removed'
pass 'concurrent updater is rejected and its lock remains untouched'

if grep -R -q '^systemctl stop ' "$SUITE"/*/state/events; then fail 'update must not pre-stop the service'; fi
printf 'All 12 one-click update behavior tests passed.\n'
