"""Preflight V1, back up development, and rehearse restoration in an empty test DB.

Executed over authenticated SSH by prepare-dev-backup.py. Credentials arrive only
on SSH stdin; mysql children receive them in a short-lived process environment.
No development DDL, global locks, account changes, or service changes are run.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

DEV = "account_book_dev"
TEST = "account_book_test"
# Observed from this server's mysql --version output, not a filesystem-wide search.
KNOWN_CLIENT_DIRS = (Path("/opt/mysql8/bin"),)
TABLES = sorted([
    "app_config", "app_user", "auth_session", "ledger", "ledger_member",
    "ledger_invite", "category", "fund_account", "book_entry", "audit_log",
    "flyway_schema_history",
])


class OpsError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise OpsError(message)


def emit(event, **details):
    print(json.dumps({"event": event, **details}), flush=True)


def resolve_client(program):
    require(program in ("mysql", "mysqldump"), "Unexpected client program")
    # mysql may be the only symlink exposed on PATH in a tarball installation.
    mysql = shutil.which("mysql")
    sibling = Path(mysql).resolve().with_name("mysqldump") if mysql else None
    if program == "mysqldump" and sibling is not None:
        if sibling.is_file() and os.access(sibling, os.X_OK):
            return str(sibling)
    executable = shutil.which(program)
    if executable:
        return str(Path(executable).resolve())
    for directory in KNOWN_CLIENT_DIRS:
        candidate = directory / program
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate.resolve())
    emit("tool_not_found", program=program,
         mysql_path=mysql, sibling_checked=str(sibling) if sibling else None,
         install_paths_checked=[str(directory / program) for directory in KNOWN_CLIENT_DIRS])
    raise OpsError(program + " was not found in checked executable locations; no installation was attempted")


def credentials_args(database, executable="mysql"):
    require(database in (DEV, TEST), "Unexpected database target")
    return [
        resolve_client(executable), "--no-defaults", "--protocol=TCP",
        "--host=127.0.0.1", "--port=3306", "--user=" + database + "_app",
        "--default-character-set=utf8mb4", "--get-server-public-key",
    ]


def environment(password):
    result = dict(os.environ)
    result["MYSQL_PWD"] = password
    result["MYSQL_HISTFILE"] = "/dev/null"
    # MySQL 8.0 still reads .mylogin.cnf with --no-defaults, and does not
    # support --no-login-paths. Override only this child process's login file.
    result["MYSQL_TEST_LOGIN_FILE"] = "/dev/null"
    return result


def checked_run(args, password, *, sql=None, source=None, timeout=90):
    options = {"env": environment(password), "stdout": subprocess.PIPE,
               "stderr": subprocess.PIPE, "timeout": timeout}
    if source is not None:
        options["stdin"] = source
    elif sql is not None:
        options["input"] = sql.encode("utf-8")
    result = subprocess.run(args, **options)
    if result.returncode:
        # Do not echo SQL, arguments, server messages, or credential-bearing data.
        match = re.search(rb"ERROR\s+(\d+)", result.stderr)
        code = match.group(1).decode("ascii") if match else "unavailable"
        raise OpsError("MySQL command failed; numeric error code=" + code)
    return result.stdout


def query(database, password, sql):
    args = credentials_args(database) + [
        "--batch", "--raw", "--skip-column-names", "--connect-timeout=10",
        "--database=" + database,
    ]
    output = checked_run(args, password, sql=sql).decode("utf-8").strip()
    return [line.split("\t") for line in output.splitlines()] if output else []


def identity(database, password):
    rows = query(database, password,
                 "SELECT DATABASE(),CURRENT_USER(),VERSION(),@@server_uuid;")
    require(len(rows) == 1 and len(rows[0]) == 4, "Invalid identity response")
    db, user, version, uuid = rows[0]
    require(db == database, "Wrong selected database")
    require(user == database + "_app@127.0.0.1", "Wrong authenticated account")
    require(version.startswith("8.0."), "Expected MySQL 8.0 server")
    return {"database": db, "current_user": user, "version": version, "uuid": uuid}


def tables(database, password):
    return query(database, password,
                 "SELECT TABLE_NAME,TABLE_TYPE,COALESCE(ENGINE,'') "
                 "FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() "
                 "ORDER BY TABLE_NAME;")


def extra_objects(database, password):
    row = query(database, password,
                "SELECT (SELECT COUNT(*) FROM information_schema.routines "
                "WHERE ROUTINE_SCHEMA=DATABASE()),"
                "(SELECT COUNT(*) FROM information_schema.triggers "
                "WHERE TRIGGER_SCHEMA=DATABASE()),"
                "(SELECT COUNT(*) FROM information_schema.events "
                "WHERE EVENT_SCHEMA=DATABASE());")[0]
    require(all(int(value) == 0 for value in row),
            "Unexpected routines, triggers or events; manual inspection required")


def row_counts(database, password):
    sql = " UNION ALL ".join(
        "SELECT '" + table + "',COUNT(*) FROM `" + table + "`"
        for table in TABLES
    ) + ";"
    return {table: int(count) for table, count in query(database, password, sql)}


def validate_dump_target(sql_bytes):
    require(re.search(rb"(?im)^\s*(?:/\*!\d{5,6}\s*)?"
                      rb"(?:(?:CREATE|ALTER|DROP)\s+(?:DATABASE|SCHEMA)\b|USE\b)",
                      sql_bytes) is None,
            "Dump contains a database-changing statement")
    # installed_by legitimately contains account_book_dev_app. Reject the
    # database identifier followed by a qualifier, not a substring in row data.
    require(re.search(rb"(?i)(?:`account_book_dev`|\baccount_book_dev\b)\s*\.",
                      sql_bytes) is None,
            "Dump contains a development database reference; cannot rehearse safely")


def dump(database, password, destination):
    args = credentials_args(database, "mysqldump") + [
        "--single-transaction", "--quick", "--set-gtid-purged=OFF",
        "--no-tablespaces", "--column-statistics=0", "--hex-blob",
        "--order-by-primary", "--skip-comments", "--skip-dump-date",
        "--skip-add-drop-table", "--skip-add-locks", "--skip-triggers",
        "--skip-lock-tables", "--result-file=" + str(destination), database,
    ]
    require(not destination.exists(), "Backup destination already exists")
    checked_run(args, password, timeout=300)
    require(destination.is_file() and destination.stat().st_size > 0,
            "Empty or missing backup")
    os.chmod(destination, 0o600)
    return hashlib.sha256(destination.read_bytes()).hexdigest()


def check_tools():
    for program in ("mysql", "mysqldump"):
        executable = resolve_client(program)
        version = subprocess.run([executable, "--version"], check=True,
                                 capture_output=True, text=True, timeout=15,
                                 env=environment("")).stdout.strip()
        require(re.search(r"\b8\.0\.\d+", version) is not None,
                "Expected server-side MySQL 8.0 client")
        emit("tool_version", program=program, executable=executable, version=version)
        # --help parses the actual common options without opening a DB connection.
        probe = subprocess.run(credentials_args(DEV, program) + ["--help"],
                               env=environment(""), stdin=subprocess.DEVNULL,
                               capture_output=True, text=True, timeout=15)
        require(probe.returncode == 0,
                program + " client options were rejected before database connection")
        emit("tool_options_validated", program=program)


def prepare(request):
    os.umask(0o077)
    check_tools()
    dev_password = request["dev_password"]
    test_password = request["test_password"]
    require(bool(dev_password) and bool(test_password), "Empty password")
    dev_id = identity(DEV, dev_password)
    test_id = identity(TEST, test_password)
    require(dev_id["uuid"] == test_id["uuid"], "Database instances differ")
    require(not tables(TEST, test_password),
            "Test schema is not empty; existing objects will not be overwritten")
    extra_objects(TEST, test_password)
    actual = tables(DEV, dev_password)
    require([row[0] for row in actual] == TABLES,
            "Development table inventory differs from immutable V1")
    require(all(row[1:] == ["BASE TABLE", "InnoDB"] for row in actual),
            "Expected only InnoDB base tables")
    extra_objects(DEV, dev_password)
    schema_settings = query(DEV, dev_password,
        "SELECT DEFAULT_CHARACTER_SET_NAME,DEFAULT_COLLATION_NAME "
        "FROM information_schema.schemata WHERE SCHEMA_NAME=DATABASE();")[0]
    require(query(TEST, test_password,
        "SELECT DEFAULT_CHARACTER_SET_NAME,DEFAULT_COLLATION_NAME "
        "FROM information_schema.schemata WHERE SCHEMA_NAME=DATABASE();")[0]
        == schema_settings, "Development/test database charset or collation differs")
    history = query(DEV, dev_password,
        "SELECT installed_rank,version,type,script,checksum,success "
        "FROM flyway_schema_history ORDER BY installed_rank;")
    require(history == [["1", "1", "SQL", "V1__init_schema.sql",
                         str(request["v1_checksum"]), "1"]],
            "Flyway history/checksum differs from the expected successful original V1")
    guard = query(DEV, dev_password,
        "SELECT (SELECT COUNT(*) FROM ledger),"
        "(SELECT COUNT(*) FROM ledger WHERE id<>1),"
        "(SELECT COUNT(*) FROM ledger_invite WHERE used_count<>0 "
        "OR status NOT IN ('ACTIVE','REVOKED')); ")[0]
    require(int(guard[0]) <= 1 and guard[1:] == ["0", "0"],
            "Legacy data cannot be mapped safely by V2")
    unexpected = query(DEV, dev_password,
        "SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() "
        "AND ((TABLE_NAME='ledger' AND COLUMN_NAME='singleton_key') "
        "OR (TABLE_NAME='ledger_invite' AND COLUMN_NAME IN ('used_by','used_at')));")[0][0]
    require(unexpected == "0", "Partial V2 schema detected")
    legacy_columns = query(DEV, dev_password,
        "SELECT TABLE_NAME,COLUMN_NAME,DATA_TYPE,COALESCE(CHARACTER_MAXIMUM_LENGTH,0) "
        "FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() "
        "AND ((TABLE_NAME='audit_log' AND COLUMN_NAME='request_id') "
        "OR (TABLE_NAME='book_entry' AND COLUMN_NAME='member_id') "
        "OR (TABLE_NAME='ledger_invite' AND COLUMN_NAME IN ('max_uses','used_count'))) "
        "ORDER BY TABLE_NAME,COLUMN_NAME;")
    require(legacy_columns == [["audit_log", "request_id", "char", "36"],
        ["book_entry", "member_id", "bigint", "0"],
        ["ledger_invite", "max_uses", "tinyint", "0"],
        ["ledger_invite", "used_count", "tinyint", "0"]],
        "Legacy columns differ from V1")
    counts_before = row_counts(DEV, dev_password)
    size = int(query(DEV, dev_password,
        "SELECT COALESCE(SUM(DATA_LENGTH+INDEX_LENGTH),0) "
        "FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE();")[0][0])
    require(size < 256 * 1024 * 1024, "Development database exceeds this rehearsal size limit")
    require(shutil.disk_usage("/var/backups").free > max(128 * 1024 * 1024, size * 4),
            "Insufficient backup disk space")
    emit("preflight_passed", identity=dev_id, table_counts=counts_before,
         history=history, unsafe_ledgers=int(guard[1]), unsafe_invites=int(guard[2]))

    directory = Path(tempfile.mkdtemp(prefix="mytallybook-dev-v2-", dir="/var/backups"))
    backup = directory / "account_book_dev-v1.sql"
    emit("backup_directory_created", directory=str(directory))
    backup_hash = dump(DEV, dev_password, backup)
    sql_bytes = backup.read_bytes()
    validate_dump_target(sql_bytes)
    emit("dump_target_validated",
         application_account_mentions=sql_bytes.count(b"account_book_dev_app"))
    require(row_counts(DEV, dev_password) == counts_before,
            "Development data changed during backup; stop before rehearsal")
    emit("backup_created", path=str(backup), sha256=backup_hash, bytes=backup.stat().st_size)

    identity(TEST, test_password)
    require(not tables(TEST, test_password), "Test schema changed before restore")
    emit("test_restore_started", database=TEST)
    with backup.open("rb") as source:
        checked_run(credentials_args(TEST) + ["--binary-mode", "--database=" + TEST],
                    test_password, source=source, timeout=300)
    require([row[0] for row in tables(TEST, test_password)] == TABLES,
            "Restored table inventory mismatch")
    extra_objects(TEST, test_password)
    require(row_counts(TEST, test_password) == counts_before, "Restored row count mismatch")
    restored_hash = dump(TEST, test_password, directory / "test-restore-verification.sql")
    require(restored_hash == backup_hash, "Restored schema/data dump SHA256 mismatch")
    emit("restore_verified", sha256=restored_hash, table_counts=counts_before)

    identity(TEST, test_password)
    require([row[0] for row in tables(TEST, test_password)] == TABLES,
            "Unexpected test objects; refusing cleanup")
    cleanup = "SET FOREIGN_KEY_CHECKS=0;\n" + "\n".join(
        "DROP TABLE `account_book_test`.`" + name + "`;" for name in TABLES
    ) + "\nSET FOREIGN_KEY_CHECKS=1;"
    query(TEST, test_password, cleanup)
    identity(TEST, test_password)
    require(not tables(TEST, test_password), "Test cleanup did not finish")
    extra_objects(TEST, test_password)
    require(row_counts(DEV, dev_password) == counts_before,
            "Development row counts changed during rehearsal")
    manifest = {"status": "PREPARED_NOT_MIGRATED", "identity": dev_id,
                "backup": str(backup), "sha256": backup_hash,
                "bytes": backup.stat().st_size, "v1_checksum": request["v1_checksum"],
                "table_counts": counts_before, "restore_verified": True,
                "test_schema_empty": True, "development_migrated": False}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    emit("complete", **manifest)


if __name__ == "__main__":
    try:
        request = json.loads(sys.stdin.readline())
        if request.get("action") == "check_tools":
            check_tools()
            emit("tools_ready")
        else:
            prepare(request)
    except OpsError as error:
        emit("failed", reason=str(error))
        sys.exit(1)
    except Exception as error:
        emit("failed", reason="Operation stopped", error_type=type(error).__name__)
        sys.exit(1)
