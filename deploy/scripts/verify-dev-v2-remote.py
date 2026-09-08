"""Read-only development snapshot recheck before the authorized V2 migration."""
import hashlib
from pathlib import Path
import tempfile


def precheck(request, ops):
    manifest = request["manifest"]
    password = request["dev_password"]
    backup = Path(manifest["backup"])
    ops.require(str(backup) == "/var/backups/mytallybook-dev-v2-3ni425zj/account_book_dev-v1.sql",
                "Unexpected verified backup path")
    ops.require(backup.is_file() and not backup.is_symlink(), "Verified backup is missing or redirected")
    ops.require(hashlib.sha256(backup.read_bytes()).hexdigest() == manifest["sha256"],
                "Verified server backup hash changed")
    ops.check_tools()
    current = ops.identity(ops.DEV, password)
    ops.require(current == manifest["identity"], "Development instance or identity changed")
    running = []
    for directory in Path("/proc").iterdir():
        if not directory.name.isdigit():
            continue
        try:
            if (directory / "comm").read_text().strip() not in ("java", "javaw"):
                continue
            command = (directory / "cmdline").read_bytes().lower()
            if any(marker in command for marker in (b"account-book-server", b"com.mytallybook", b"mytallybook")):
                running.append(int(directory.name))
        except (FileNotFoundError, ProcessLookupError):
            continue
    ops.require(not running, "MyTallyBook server process is running; stop only this project before migration")
    connections = ops.query(ops.DEV, password,
        "SELECT COUNT(*) FROM information_schema.processlist "
        "WHERE USER='account_book_dev_app' AND ID<>CONNECTION_ID();")[0][0]
    ops.require(connections == "0", "Development account has other connections; disconnect them before migration")
    ops.require([row[0] for row in ops.tables(ops.DEV, password)] == ops.TABLES,
                "Development table inventory changed")
    ops.extra_objects(ops.DEV, password)
    history = ops.query(ops.DEV, password,
        "SELECT installed_rank,version,type,script,checksum,success "
        "FROM flyway_schema_history ORDER BY installed_rank;")
    ops.require(history == [["1", "1", "SQL", "V1__init_schema.sql", str(manifest["v1_checksum"]), "1"]],
                "Development V1 history changed")
    ops.require(ops.row_counts(ops.DEV, password) == manifest["table_counts"],
                "Development row counts changed since verified backup")
    directory = Path(tempfile.mkdtemp(prefix="mytallybook-dev-v2-recheck-", dir="/var/backups"))
    snapshot = directory / "current-v1.sql"
    current_hash = ops.dump(ops.DEV, password, snapshot)
    ops.require(current_hash == manifest["sha256"],
                "Current development snapshot differs from verified backup; migration was not started")
    ops.emit("migration_precheck_passed", identity=current, snapshot=str(snapshot),
             sha256=current_hash, history=history, project_processes=0, other_dev_connections=0)
