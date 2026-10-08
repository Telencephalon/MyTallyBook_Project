"""Execute the complete cleanup SQL on a disposable, self-started MySQL server.

Run with Python's standard library only:
  python deploy/scripts/tests/test_cleanup_departed_members_sql.py --mysql-bin "PATH/bin"

The runner accepts no existing server, socket, credentials, or database endpoint.
It initializes a unique main-checkout target directory, binds its own child server
to loopback, verifies its datadir before SQL, and shuts down only that child.
Datadir and diagnostics are retained under target; no recursive removal occurs.
"""

import argparse
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import unittest
import uuid


ROOT = Path(__file__).resolve().parents[3]
SQL_FILE = ROOT / "deploy/sql/cleanup-departed-members.sql"
WINDOWS_MYSQL_BIN = Path("D:/Work/Tools/MySql/MySQL Server 5.7/bin")
MYSQL_BIN = None


SCHEMA_AND_DATA = """
CREATE DATABASE account_book_dev CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE account_book_dev;
CREATE TABLE app_config (
  id TINYINT UNSIGNED PRIMARY KEY, initialized TINYINT NOT NULL
) ENGINE=InnoDB;
CREATE TABLE app_user (
  id BIGINT UNSIGNED PRIMARY KEY, nickname VARCHAR(64), status VARCHAR(16) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE ledger (
  id BIGINT UNSIGNED PRIMARY KEY, singleton_key TINYINT, owner_user_id BIGINT UNSIGNED NOT NULL,
  status VARCHAR(16) NOT NULL, name VARCHAR(64),
  FOREIGN KEY (owner_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB;
CREATE TABLE ledger_member (
  id BIGINT UNSIGNED PRIMARY KEY, ledger_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL, role VARCHAR(16) NOT NULL,
  display_name VARCHAR(64), status VARCHAR(16) NOT NULL, joined_at DATETIME NOT NULL,
  removed_at DATETIME, UNIQUE KEY membership (ledger_id, user_id),
  FOREIGN KEY (ledger_id) REFERENCES ledger(id), FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB;
CREATE TABLE book_entry (
  id BIGINT UNSIGNED PRIMARY KEY, ledger_id BIGINT UNSIGNED NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL, deleted_at DATETIME, amount DECIMAL(15,2), note VARCHAR(64),
  FOREIGN KEY (ledger_id) REFERENCES ledger(id), FOREIGN KEY (created_by) REFERENCES app_user(id)
) ENGINE=InnoDB;
CREATE TABLE audit_log (id BIGINT PRIMARY KEY, details VARCHAR(64)) ENGINE=InnoDB;
CREATE TABLE auth_session (id BIGINT PRIMARY KEY, token_hash VARCHAR(64)) ENGINE=InnoDB;
CREATE TABLE category (id BIGINT PRIMARY KEY, name VARCHAR(64)) ENGINE=InnoDB;
CREATE TABLE fund_account (id BIGINT PRIMARY KEY, balance DECIMAL(15,2)) ENGINE=InnoDB;
CREATE TABLE ledger_invite (id BIGINT PRIMARY KEY, status VARCHAR(16)) ENGINE=InnoDB;
INSERT INTO app_config VALUES (1, 1);
INSERT INTO app_user VALUES
  (1, 'owner', 'ACTIVE'), (2, 'current member', 'ACTIVE'), (3, 'left member', 'ACTIVE'),
  (4, 'removed disabled member', 'DISABLED'), (5, 'disabled current member', 'DISABLED'),
  (6, 'dirty inactive owner role', 'ACTIVE'), (7, 'removed administrator', 'ACTIVE'),
  (8, 'account without membership', 'ACTIVE'), (9, 'other ledger owner', 'ACTIVE'),
  (10, 'other ledger departed member', 'ACTIVE'), (11, 'other ledger deleted account', 'DELETED');
INSERT INTO ledger VALUES (1, 1, 1, 'ACTIVE', 'fixed ledger'), (2, 2, 9, 'ACTIVE', 'other ledger');
INSERT INTO ledger_member VALUES
  (101, 1, 1, 'OWNER', 'owner alias', 'ACTIVE', '2026-01-01', NULL),
  (102, 1, 2, 'MEMBER', '', 'ACTIVE', '2026-01-01', NULL),
  (103, 1, 3, 'MEMBER', 'left alias', 'LEFT', '2026-01-01', '2026-02-01'),
  (104, 1, 4, 'MEMBER', 'removed alias', 'REMOVED', '2026-01-01', '2026-02-02'),
  (105, 1, 5, 'MEMBER', NULL, 'ACTIVE', '2026-01-01', NULL),
  (106, 1, 6, 'OWNER', 'dirty owner', 'LEFT', '2026-01-01', '2026-02-03'),
  (107, 1, 7, 'ADMIN', 'admin alias', 'REMOVED', '2026-01-01', '2026-02-04'),
  (201, 2, 9, 'OWNER', NULL, 'ACTIVE', '2026-01-01', NULL),
  (202, 2, 10, 'MEMBER', NULL, 'LEFT', '2026-01-01', '2026-02-05'),
  (203, 2, 11, 'MEMBER', NULL, 'ACTIVE', '2026-01-01', NULL);
INSERT INTO book_entry VALUES
  (301, 1, 1, NULL, 11.11, 'owner bill'), (302, 1, 2, NULL, 22.22, 'current bill'),
  (303, 1, 3, NULL, 33.33, 'left bill'),
  (304, 1, 3, '2026-03-01', 44.44, 'soft-deleted left bill'),
  (305, 1, 4, NULL, 55.55, 'removed bill'), (306, 1, 5, NULL, 66.66, 'disabled bill'),
  (307, 1, 6, NULL, 77.77, 'dirty owner bill'), (308, 1, 7, NULL, 88.88, 'admin bill'),
  (309, 2, 10, '2026-03-02', 99.99, 'other ledger soft-deleted bill');
INSERT INTO audit_log VALUES (1, 'retain audit');
INSERT INTO auth_session VALUES (1, 'retain session');
INSERT INTO category VALUES (1, 'retain category');
INSERT INTO fund_account VALUES (1, 123.45);
INSERT INTO ledger_invite VALUES (1, 'REVOKED');
"""


def mysql_bin_directory():
    if MYSQL_BIN is not None:
        return MYSQL_BIN.resolve()
    if WINDOWS_MYSQL_BIN.is_dir():
        return WINDOWS_MYSQL_BIN
    executable = shutil.which("mysqld")
    if executable:
        return Path(executable).resolve().parent
    raise RuntimeError("MySQL tools missing: specify the local binaries with --mysql-bin PATH")


class LocalMySQL:
    def __init__(self):
        self.bin = mysql_bin_directory()
        suffix = ".exe" if os.name == "nt" else ""
        self.mysql = self.bin / ("mysql" + suffix)
        self.mysqld = self.bin / ("mysqld" + suffix)
        for executable in (self.mysql, self.mysqld):
            if not executable.is_file():
                raise RuntimeError("MySQL executable missing: " + str(executable))
        self.directory = ROOT / "target" / ("cleanup-departed-members-sql-" + uuid.uuid4().hex)
        self.datadir = self.directory / "data"
        self.process = None
        self.server_log = None
        self.identity_verified = False
        self.command_number = 0
        self.environment = os.environ.copy()
        self.environment.pop("MYSQL_PWD", None)
        self.environment["MYSQL_TEST_LOGIN_FILE"] = str(self.directory / "no-login-path.cnf")
        self.creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)

    def start(self):
        self.datadir.mkdir(parents=True)
        print("Local SQL fixture (retained): " + str(self.directory), file=sys.stderr, flush=True)
        initialize = subprocess.run(
            [str(self.mysqld), "--no-defaults", "--initialize-insecure",
             "--basedir=" + str(self.bin.parent), "--datadir=" + str(self.datadir)],
            cwd=ROOT, env=self.environment, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=90, creationflags=self.creationflags,
        )
        (self.directory / "initialize.stdout.log").write_text(initialize.stdout, encoding="utf-8")
        (self.directory / "initialize.stderr.log").write_text(initialize.stderr, encoding="utf-8")
        if initialize.returncode:
            raise RuntimeError("MySQL fixture initialization failed: " + initialize.stderr)
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            self.port = reservation.getsockname()[1]
        self.server_log = (self.directory / "mysqld.console.log").open("w", encoding="utf-8")
        self.process = subprocess.Popen(
            [str(self.mysqld), "--no-defaults", "--console",
             "--basedir=" + str(self.bin.parent), "--datadir=" + str(self.datadir),
             "--bind-address=127.0.0.1", "--port=" + str(self.port),
             "--pid-file=" + str(self.directory / "mysqld.pid"),
             "--innodb-buffer-pool-size=32M", "--skip-log-bin"],
            cwd=ROOT, env=self.environment, stdout=self.server_log, stderr=subprocess.STDOUT,
            creationflags=self.creationflags,
        )
        deadline = time.monotonic() + 30
        last_error = "no client probe completed"
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("MySQL fixture exited during startup; see " + str(self.directory))
            result = self.run("SELECT @@datadir, @@port;", database=None, record=False)
            last_error = result.stderr
            (self.directory / "startup-probe.stderr.log").write_text(last_error, encoding="utf-8")
            if result.returncode == 0:
                datadir, port = result.stdout.strip().split("\t")
                if Path(datadir).resolve() != self.datadir.resolve() or int(port) != self.port:
                    raise RuntimeError("Refusing endpoint: it is not the child fixture datadir/port")
                self.identity_verified = True
                return
            if any(error in result.stderr for error in
                   ("Access denied", "not allowed to connect", "unknown option", "(10013)")):
                raise RuntimeError("MySQL fixture startup probe failed: " + result.stderr)
            time.sleep(0.2)
        raise RuntimeError("MySQL fixture startup timed out: " + last_error + "\nSee " + str(self.directory))

    def run(self, sql, database="account_book_dev", force=False, record=True):
        command = [str(self.mysql), "--no-defaults", "--protocol=TCP", "--host=127.0.0.1",
                   "--port=" + str(self.port), "--user=root", "--batch", "--raw",
                   "--skip-reconnect", "--connect-timeout=1", "--default-character-set=utf8mb4"]
        if database:
            if not self.identity_verified:
                raise RuntimeError("Fixture identity must be verified before database access")
            command.append("--database=" + database)
        if force:
            command.append("--force")
        if not record:
            command.append("--skip-column-names")
        result = subprocess.run(command, input=sql, cwd=ROOT, env=self.environment,
                                capture_output=True, text=True, encoding="utf-8", errors="replace",
                                timeout=30, creationflags=self.creationflags)
        if record:
            self.command_number += 1
            prefix = self.directory / ("command-%03d" % self.command_number)
            prefix.with_suffix(".stdout.log").write_text(result.stdout, encoding="utf-8")
            prefix.with_suffix(".stderr.log").write_text(result.stderr, encoding="utf-8")
        return result

    def execute(self, sql, database="account_book_dev"):
        result = self.run(sql, database)
        if result.returncode:
            raise RuntimeError("Fixture SQL failed: " + result.stderr)
        return result.stdout

    def reset(self):
        self.execute("DROP DATABASE IF EXISTS account_book_dev;\n" + SCHEMA_AND_DATA, database=None)

    def snapshot(self):
        tables = self.run("SHOW TABLES;", record=False)
        if tables.returncode:
            raise RuntimeError("Fixture snapshot failed: " + tables.stderr)
        values = {}
        for table in tables.stdout.splitlines():
            result = self.run("SELECT * FROM `%s` ORDER BY id;" % table, record=False)
            if result.returncode:
                raise RuntimeError("Fixture snapshot failed: " + result.stderr)
            values[table] = result.stdout
        return values

    def stop(self):
        try:
            if self.process is not None and self.process.poll() is None:
                if self.identity_verified:
                    try:
                        self.run("SHUTDOWN;", database=None)
                        self.process.wait(timeout=15)
                    except (subprocess.TimeoutExpired, OSError):
                        pass
                if self.process.poll() is None:
                    self.process.terminate()
                    try:
                        self.process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        self.process.kill()
                        self.process.wait(timeout=10)
        finally:
            if self.server_log is not None:
                self.server_log.close()
                self.server_log = None


def result_rows(stdout, first_column):
    """Read one actual mysql batch result set without relying on source SQL text."""
    lines = stdout.splitlines()
    for index, line in enumerate(lines):
        if line.split("\t")[0] == first_column:
            rows = []
            for candidate in lines[index + 1:]:
                fields = candidate.split("\t")
                if fields[0].isdigit() or fields[0] in ("APPLIED", "PREVIEW_ROLLED_BACK"):
                    rows.append(fields)
                else:
                    break
            return rows
    raise AssertionError("Expected result set missing: %s\n%s" % (first_column, stdout))


class CleanupDepartedMembersSQLTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not SQL_FILE.is_file() or not SQL_FILE.stat().st_size:
            raise RuntimeError("Cleanup SQL missing or empty: " + str(SQL_FILE))
        cls.sql = SQL_FILE.read_text(encoding="utf-8-sig")
        cls.mysql = LocalMySQL()
        cls.addClassCleanup(cls.mysql.stop)
        cls.mysql.start()

    def setUp(self):
        self.mysql.reset()

    def cleanup(self, settings="", force=False):
        # Execute the repository SQL in full and unchanged in a fresh connection.
        return self.mysql.run(settings + "\n" + self.sql, force=force)

    def assert_success(self, result):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("ERROR", result.stderr)

    def assert_only_members_removed(self, before, expected_ids):
        after = self.mysql.snapshot()
        self.assertEqual(set(before), set(after))
        for table in before:
            if table != "ledger_member":
                self.assertEqual(before[table], after[table], table + " was changed")
        remaining = [int(row.split("\t")[0]) for row in after["ledger_member"].splitlines()]
        self.assertEqual(expected_ids, remaining)
        expected_rows = "".join(row + "\n" for row in before["ledger_member"].splitlines()
                                if int(row.split("\t")[0]) in expected_ids)
        self.assertEqual(expected_rows, after["ledger_member"], "retained memberships changed")

    def assert_rejected_without_mutation(self, mutation="", settings="", force=False):
        if mutation:
            self.mysql.execute(mutation)
        before = self.mysql.snapshot()
        result = self.cleanup("SET @cleanup_apply=1;\n" + settings, force=force)
        # --force may return 0 while continuing after errors; stderr still must identify failure.
        if not force:
            self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("ERROR", result.stderr)
        self.assertEqual(before, self.mysql.snapshot(), "rejected cleanup changed persistent rows")

    def test_default_preview_reports_candidates_and_preserves_all_persistent_rows(self):
        before = self.mysql.snapshot()
        result = self.cleanup()
        self.assert_success(result)
        candidates = result_rows(result.stdout, "departed_member_id")
        self.assertEqual([103, 104, 107], [int(row[0]) for row in candidates])
        self.assertEqual([2, 1, 1], [int(row[-1]) for row in candidates])
        creators = result_rows(result.stdout, "member_id")
        self.assertEqual([["101", "1", "owner alias"], ["102", "2", "current member"]], creators)
        self.assertEqual([["PREVIEW_ROLLED_BACK", "0", "11", "9"]], result_rows(result.stdout, "result"))
        self.assertEqual(before, self.mysql.snapshot())

    def test_apply_deletes_only_departed_nonowners_and_keeps_accounts_and_history(self):
        before = self.mysql.snapshot()
        result = self.cleanup("SET @cleanup_apply=1;")
        self.assert_success(result)
        self.assertEqual([["APPLIED", "3", "11", "9"]], result_rows(result.stdout, "result"))
        # ACTIVE disabled user, inactive OWNER role, and the other ledger all survive.
        self.assert_only_members_removed(before, [101, 102, 105, 106, 201, 202, 203])

    def test_repeated_apply_is_idempotent(self):
        first = self.cleanup("SET @cleanup_apply=1;")
        self.assert_success(first)
        before = self.mysql.snapshot()
        second = self.cleanup("SET @cleanup_apply=1;")
        self.assert_success(second)
        self.assertEqual([["APPLIED", "0", "11", "9"]], result_rows(second.stdout, "result"))
        self.assertEqual(before, self.mysql.snapshot())

    def test_user_filter_deletes_only_the_requested_departed_user(self):
        before = self.mysql.snapshot()
        result = self.cleanup("SET @cleanup_apply=1, @cleanup_user_id=3;")
        self.assert_success(result)
        self.assertEqual([103], [int(row[0]) for row in result_rows(result.stdout, "departed_member_id")])
        self.assertEqual([["APPLIED", "1", "11", "9"]], result_rows(result.stdout, "result"))
        self.assert_only_members_removed(before, [101, 102, 104, 105, 106, 107, 201, 202, 203])

    def test_active_or_unknown_user_filter_is_noop(self):
        for user_id in (2, 5, 999):
            with self.subTest(user_id=user_id):
                before = self.mysql.snapshot()
                result = self.cleanup("SET @cleanup_apply=1, @cleanup_user_id=%d;" % user_id)
                self.assert_success(result)
                self.assertEqual([["APPLIED", "0", "11", "9"]], result_rows(result.stdout, "result"))
                self.assertEqual(before, self.mysql.snapshot())

    def test_uninitialized_or_missing_configuration_aborts(self):
        for mutation in ("UPDATE app_config SET initialized=0 WHERE id=1;", "DELETE FROM app_config;"):
            with self.subTest(mutation=mutation):
                self.mysql.reset()
                self.assert_rejected_without_mutation(mutation)

    def test_invalid_owner_or_ledger_state_aborts(self):
        mutations = (
            "UPDATE app_user SET status='DISABLED' WHERE id=1;",
            "UPDATE ledger_member SET status='LEFT' WHERE id=101;",
            "UPDATE ledger_member SET role='MEMBER' WHERE id=101;",
            "UPDATE ledger SET owner_user_id=2 WHERE id=1;",
            "UPDATE ledger_member SET role='OWNER' WHERE id=102;",
            "UPDATE ledger SET status='ARCHIVED' WHERE id=1;",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.mysql.reset()
                self.assert_rejected_without_mutation(mutation)

    def test_wrong_database_guard_aborts(self):
        self.assert_rejected_without_mutation(settings="SET @cleanup_expected_database='wrong_database';")

    def test_legacy_member_id_column_aborts(self):
        self.assert_rejected_without_mutation("ALTER TABLE book_entry ADD member_id BIGINT UNSIGNED NULL;")

    def test_referencing_foreign_key_aborts(self):
        self.assert_rejected_without_mutation("""
            CREATE TABLE reference_to_members (
              id BIGINT PRIMARY KEY, association_id BIGINT UNSIGNED,
              FOREIGN KEY (association_id) REFERENCES ledger_member(id) ON DELETE CASCADE
            ) ENGINE=InnoDB;
            INSERT INTO reference_to_members VALUES (1, 103);
        """)

    def test_membership_delete_trigger_aborts(self):
        self.assert_rejected_without_mutation("""
            CREATE TRIGGER dirty_membership_delete BEFORE DELETE ON ledger_member
            FOR EACH ROW UPDATE audit_log SET details='trigger ran' WHERE id=1;
        """)

    def test_nontransactional_core_table_aborts(self):
        self.assert_rejected_without_mutation("ALTER TABLE app_config ENGINE=MyISAM;")

    def test_missing_singleton_key_aborts(self):
        self.assert_rejected_without_mutation("ALTER TABLE ledger DROP COLUMN singleton_key;")

    def test_invalid_direct_sql_parameters_aborts(self):
        settings = ("SET @cleanup_apply=2;", "SET @cleanup_ledger_id=2;",
                    "SET @cleanup_user_id=0;", "SET @cleanup_user_id=-1;",
                    "SET SESSION foreign_key_checks=0;")
        for setting in settings:
            with self.subTest(setting=setting):
                self.assert_rejected_without_mutation(settings=setting)

    def test_force_client_cannot_commit_after_failed_guard(self):
        for mutation in ("UPDATE app_config SET initialized=0 WHERE id=1;",
                         "ALTER TABLE book_entry ADD member_id BIGINT UNSIGNED NULL;"):
            with self.subTest(mutation=mutation):
                self.mysql.reset()
                self.assert_rejected_without_mutation(mutation, force=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mysql-bin", type=Path, help="directory containing local mysql and mysqld binaries")
    options, unittest_arguments = parser.parse_known_args()
    MYSQL_BIN = options.mysql_bin
    unittest.main(argv=[sys.argv[0]] + unittest_arguments, verbosity=2)
