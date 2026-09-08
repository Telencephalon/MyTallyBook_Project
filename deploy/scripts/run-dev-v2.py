"""Interactive, fixed-target launcher for the already approved development V2."""
import base64
import importlib.util
import json
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = Path(__file__).resolve().parent
JAVA = Path("D:/Work/Config/JDK/JDK/jdk21/bin/java.exe")
BACKUP_RUN = ROOT / "backup/dev-v2/20260905-115514-895429"
EXPECTED_HASH = "d24dbef7d6fbb39ca0bd3edaf6ace687d2a3901e5209cfcab17b76587e7d13e7"
EXPECTED_BACKUP = "/var/backups/mytallybook-dev-v2-3ni425zj/account_book_dev-v1.sql"
EXPECTED_UUID = "20f56ed0-988d-11f1-aeb3-fa163ed2bb15"
EXPECTED_V2_SHA256 = "9a69661dcec91a27b6a631a64d29217ed894c68cc8bb9622795806c82bc49ad8"
RUNNER_EVENT_PREFIX = "V2_EVENT "

spec = importlib.util.spec_from_file_location("backup_launcher", SCRIPTS / "prepare-dev-backup.py")
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


def validate_backup_metadata(record):
    if (record.get("status") != "PREPARED_NOT_MIGRATED"
            or any(record.get(key) is not True for key in ("restore_verified", "download_verified", "test_schema_empty"))
            or record.get("development_migrated") is not False):
        raise ValueError("A verified backup and restore rehearsal are required")
    if record.get("identity") != {"database": "account_book_dev", "current_user": "account_book_dev_app@127.0.0.1",
                                  "version": "8.0.46", "uuid": EXPECTED_UUID}:
        raise ValueError("Unexpected verified backup identity")
    if record.get("backup") != EXPECTED_BACKUP:
        raise ValueError("Unexpected verified backup path")
    if record.get("sha256") != EXPECTED_HASH or record.get("bytes") != 15284 or record.get("v1_checksum") != 6679958:
        raise ValueError("Verified backup fingerprint changed")


def validate_maintenance_acknowledgement(response):
    if response != "MIGRATE":
        raise ValueError("The development maintenance window was not acknowledged")


def validate_v2_fingerprint(path):
    if hashlib.sha256(path.read_bytes()).hexdigest() != EXPECTED_V2_SHA256:
        raise ValueError("Approved V2 fingerprint changed")


def local_guard():
    # Output booleans/counts only: never expose process command lines or env values.
    command = r'''
    $migrationJava = @(Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('java.exe','javaw.exe') })
    $migrationSockets = @(Get-NetTCPConnection -LocalPort 13306 -State Listen -ErrorAction SilentlyContinue)
    $migrationTunnel = $false
    if ($migrationSockets.Count -eq 1 -and $migrationSockets[0].LocalAddress -eq '127.0.0.1') {
        $migrationOwner = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $migrationSockets[0].OwningProcess)
        $migrationTunnel = $migrationOwner.Name -eq 'ssh.exe' -and $migrationOwner.CommandLine -like '*127.0.0.1:13306:127.0.0.1:3306*' -and $migrationOwner.CommandLine -like '*root@117.72.101.42*'
    }
    @{java_count=$migrationJava.Count;expected_tunnel=$migrationTunnel} | ConvertTo-Json -Compress
    '''
    result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                            capture_output=True, text=True, timeout=30, check=True)
    status = json.loads(result.stdout)
    if status["java_count"] != 0:
        raise ValueError("A local Java process is running; inspect it before migration, do not kill unrelated services")
    if status["expected_tunnel"] is not True:
        raise ValueError("The expected existing SSH tunnel on 127.0.0.1:13306 is not verified")


def remote_precheck(client, manifest, password, log):
    shared = base64.b64encode((SCRIPTS / "prepare-dev-backup-remote.py").read_bytes()).decode("ascii")
    checker = base64.b64encode((SCRIPTS / "verify-dev-v2-remote.py").read_bytes()).decode("ascii")
    source = ("import base64,json,sys,types\nops=types.ModuleType('backup_ops')\n"
              "exec(base64.b64decode('" + shared + "'),ops.__dict__)\n"
              "exec(base64.b64decode('" + checker + "'))\n"
              "try:\n precheck(json.loads(sys.stdin.readline()),ops)\n"
              "except ops.OpsError as error:\n ops.emit('failed',reason=str(error));sys.exit(1)\n"
              "except Exception as error:\n ops.emit('failed',error_type=type(error).__name__);sys.exit(1)\n")
    encoded = base64.b64encode(source.encode()).decode("ascii")
    command = 'python3 -u -c "import base64;exec(base64.b64decode(\'' + encoded + '\'))"'
    stdin, stdout, stderr = client.exec_command(command, timeout=600)
    stdin.write(json.dumps({"manifest": manifest, "dev_password": password}) + "\n")
    stdin.flush()
    stdin.channel.shutdown_write()
    verified = False
    for line in stdout:
        event = json.loads(line)
        record_event(log, event)
        verified = verified or event.get("event") == "migration_precheck_passed"
    status = stdout.channel.recv_exit_status()
    stderr.read()
    if status != 0 or not verified:
        raise ValueError("Remote snapshot recheck did not pass; V2 was not started")


def record_event(log, event):
    text = json.dumps(event)
    print(text, flush=True)
    log.write(text + "\n")
    log.flush()


def sanitized_runner_event(event):
    if not isinstance(event, dict) or not isinstance(event.get("event"), str):
        return False
    name = event["event"]
    keys = set(event)
    if name == "identity_verified":
        return keys == {"event"}
    if name == "migration_started":
        return keys == {"event", "target"} and event.get("target") == "2"
    if name == "migration_complete":
        return (keys == {"event", "versions", "migrations_executed", "v1_checksum", "v2_checksum"}
                and event.get("versions") == ["1", "2"]
                and event.get("migrations_executed") == 1
                and type(event.get("v1_checksum")) is int
                and type(event.get("v2_checksum")) is int)
    if name == "migration_failed":
        if not {"event", "exception"}.issubset(keys) or not keys.issubset(
                {"event", "exception", "sql_state", "error_code"}
        ):
            return False
        if not isinstance(event.get("exception"), str) or not re.fullmatch(
                r"[A-Za-z_$][A-Za-z0-9_.$]*", event["exception"]
        ):
            return False
        if "sql_state" in event and (not isinstance(event["sql_state"], str)
                                     or not re.fullmatch(r"[A-Za-z0-9]{5}", event["sql_state"])):
            return False
        return "error_code" not in event or type(event["error_code"]) is int
    return False


def _text_output(value):
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return str(value)


def _record_runner_event(log, event):
    try:
        record_event(log, event)
        return True
    except (OSError, ValueError):
        # record_event prints before writing, so sanitized evidence remains visible
        # even when the private evidence file becomes unavailable.
        return False


def _retain_runner_events(output, log):
    complete = None
    malformed = False
    evidence_write_failed = False
    for line in _text_output(output).splitlines():
        if not line.startswith(RUNNER_EVENT_PREFIX):
            continue
        try:
            event = json.loads(line[len(RUNNER_EVENT_PREFIX):])
        except (json.JSONDecodeError, TypeError):
            malformed = True
            continue
        if not sanitized_runner_event(event):
            malformed = True
            continue
        evidence_write_failed = not _record_runner_event(log, event) or evidence_write_failed
        if event["event"] == "migration_complete":
            complete = event
    return complete, malformed, evidence_write_failed


def _record_ambiguous_stop(log, exit_code, failure_type, evidence_write_failed=False):
    event = {"event": "migration_stopped", "exit_code": exit_code,
             "failure_type": failure_type, "ambiguous_state": True,
             "automatic_retry": False, "backup_retained": True,
             "approved_recovery_required": True}
    if evidence_write_failed:
        event["evidence_write_failed"] = True
    _record_runner_event(log, event)
    print("AMBIGUOUS MIGRATION STATE. Do not retry. Keep the verified backup and use the "
          "approved recovery procedure after inspecting retained evidence and the database.", flush=True)


def run_java_and_capture(command, env, payload, log, run=None):
    runner = subprocess.run if run is None else run
    output = ""
    exit_code = None
    failure_type = None
    try:
        process = runner(command, env=env, input=payload, capture_output=True,
                         encoding="utf-8", errors="replace", timeout=420)
        output = process.stdout
        exit_code = process.returncode
    except subprocess.TimeoutExpired as error:
        output = error.stdout if error.stdout is not None else error.output
        failure_type = "TimeoutExpired"
    except (OSError, UnicodeError) as error:
        output = getattr(error, "stdout", None)
        failure_type = type(error).__name__

    complete, malformed, evidence_write_failed = _retain_runner_events(output, log)
    if malformed:
        failure_type = failure_type or "MalformedRunnerEvent"
    if exit_code not in (None, 0):
        failure_type = failure_type or "JavaExitNonzero"
    if complete is None:
        failure_type = failure_type or "MissingMigrationComplete"
    if failure_type is not None or evidence_write_failed:
        _record_ambiguous_stop(log, exit_code, failure_type or "EvidenceWriteError",
                               evidence_write_failed)
        return None
    return complete


def main():
    if not sys.stdin.isatty():
        raise ValueError("A visible interactive console is required")
    print("=== MyTallyBook authorized DEVELOPMENT V2 migration ===", flush=True)
    print("Only account_book_dev. Keep MyTallyBook stopped and disconnect its DBeaver sessions.", flush=True)
    print("No test cleanup, no production changes, no password changes, no application startup.", flush=True)
    manifest = json.loads((BACKUP_RUN / "manifest.json").read_text(encoding="utf-8"))
    validate_backup_metadata(manifest)
    backup = BACKUP_RUN / "account_book_dev-v1.sql"
    if hashlib.sha256(backup.read_bytes()).hexdigest() != EXPECTED_HASH:
        raise ValueError("Local backup hash mismatch")
    migrations = ROOT / "account-book-server/src/main/resources/db/migration"
    v2_path = migrations / "V2__align_approved_design.sql"
    validate_v2_fingerprint(v2_path)
    v1 = launcher.v1_checksum(migrations / "V1__init_schema.sql")
    v2 = launcher.v1_checksum(v2_path)
    if v1 != manifest["v1_checksum"]:
        raise ValueError("Local immutable V1 checksum changed")
    local_guard()
    classpath = (ROOT / "account-book-server/target/dev-v2-classpath.txt").read_text(encoding="utf-8-sig").strip()
    classes = ROOT / "account-book-server/target/dev-v2-ops"
    classes.mkdir(exist_ok=True)
    subprocess.run([str(JAVA.with_name("javac.exe")), "-encoding", "UTF-8", "-cp", classpath,
                    "-d", str(classes), str(SCRIPTS / "DevV2Migration.java")], check=True)
    print("Confirm every account_book_dev client/session is disconnected and that you will not "
          "open or write account_book_dev until this launcher finishes.", flush=True)
    validate_maintenance_acknowledgement(input("Type MIGRATE to enter the maintenance window: "))
    client = launcher.paramiko.SSHClient()
    client.load_host_keys(str(Path.home() / ".ssh/known_hosts"))
    client.set_missing_host_key_policy(launcher.paramiko.RejectPolicy())
    ssh_password = launcher.hidden_password("Cloud SSH root password (hidden): ")
    dev_password = None
    try:
        client.connect(launcher.HOST, port=22, username="root", password=ssh_password,
                       look_for_keys=False, allow_agent=False, timeout=15, banner_timeout=15, auth_timeout=30)
        ssh_password = None
        client.get_transport().set_keepalive(30)
        dev_password = launcher.hidden_password("account_book_dev_app password (hidden): ")
        output = launcher.private_output_directory()
        print("Migration evidence: " + str(output), flush=True)
        with (output / "migration-events.jsonl").open("x", encoding="utf-8") as log:
            remote_precheck(client, manifest, dev_password, log)
            local_guard()
            env = dict(os.environ)
            env["ACCOUNT_BOOK_V2_ALLOWED"] = "account_book_dev"
            for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
                env.pop(name, None)
            payload = "\n".join((base64.b64encode(dev_password.encode()).decode("ascii"), EXPECTED_UUID, str(v1), str(v2))) + "\n"
            # Child diagnostics are filtered; only deliberately sanitized runner events are retained.
            validate_v2_fingerprint(v2_path)
            try:
                complete = run_java_and_capture(
                    [str(JAVA), "-cp", str(classes) + os.pathsep + classpath,
                     "DevV2Migration", str(migrations)], env, payload, log
                )
            finally:
                payload = dev_password = None
            if complete is None:
                return 1
            complete.update({"backup": EXPECTED_BACKUP, "backup_sha256": EXPECTED_HASH,
                             "status": "DEVELOPMENT_V2_VERIFIED", "application_started": False})
            try:
                (output / "migration-manifest.json").write_text(
                    json.dumps(complete, indent=2), encoding="utf-8"
                )
            except OSError:
                _record_ambiguous_stop(log, 0, "ManifestWriteError", True)
                return 1
            print("DEVELOPMENT_V2_VERIFIED. No application or WeChat login was started.", flush=True)
            return 0
    finally:
        ssh_password = dev_password = None
        client.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print("Migration launcher failed before a verified completion: " + type(error).__name__, flush=True)
        print("If Java may have started, do not retry; retain the verified backup and use the "
              "approved recovery procedure after inspection.", flush=True)
        sys.exit(1)
