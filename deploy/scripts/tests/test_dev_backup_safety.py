import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


remote = load("backup_remote", "prepare-dev-backup-remote.py")
launcher = load("backup_launcher", "prepare-dev-backup.py")


class BackupSafetyTests(unittest.TestCase):
    def test_dump_allows_development_account_name_in_flyway_history(self):
        sql = (b"CREATE TABLE `flyway_schema_history` (`installed_by` varchar(100));\n"
               b"INSERT INTO `flyway_schema_history` VALUES ('account_book_dev_app');\n")
        try:
            remote.validate_dump_target(sql)
        except remote.OpsError as error:
            self.fail("Ordinary Flyway installed_by data must be allowed: " + str(error))

    def test_dump_rejects_database_switch_and_source_qualified_tables(self):
        for sql in (b"USE `account_book_dev`;", b"CREATE DATABASE `account_book_dev`;",
                    b"INSERT INTO `account_book_dev`.`app_user` VALUES (1);",
                    b"INSERT INTO account_book_dev.app_user VALUES (1);"):
            with self.subTest(sql=sql), self.assertRaises(remote.OpsError):
                remote.validate_dump_target(sql)

    def test_dump_rejects_version_commented_database_switches(self):
        for sql in (b"/*!50000 USE `other` */;", b"CREATE SCHEMA `other`;",
                    b"ALTER DATABASE `other` CHARACTER SET utf8mb4;",
                    b"INSERT INTO ACCOUNT_BOOK_DEV.app_user VALUES (1);"):
            with self.subTest(sql=sql), self.assertRaises(remote.OpsError):
                remote.validate_dump_target(sql)

    def test_mysql80_command_arguments_do_not_use_unsupported_no_login_paths(self):
        with patch.object(remote.shutil, "which", return_value="/usr/bin/mysql"):
            for program in ("mysql", "mysqldump"):
                args = remote.credentials_args(remote.DEV, program)
                self.assertEqual("--no-defaults", args[1])
                self.assertNotIn("--no-login-paths", args)

    def test_child_environment_ignores_existing_login_path_file(self):
        with patch.dict(remote.os.environ, {"MYSQL_TEST_LOGIN_FILE": "/root/.mylogin.cnf"}):
            env = remote.environment("fixture")
            self.assertEqual("/dev/null", env["MYSQL_TEST_LOGIN_FILE"])
            self.assertEqual("/root/.mylogin.cnf", remote.os.environ["MYSQL_TEST_LOGIN_FILE"])

    def test_unsupported_client_options_stop_before_database_identity_query(self):
        def client_result(args, **kwargs):
            if "--version" in args:
                return types.SimpleNamespace(stdout="mysql Ver 8.0.46", returncode=0, stderr="")
            if "--help" in args:
                return types.SimpleNamespace(stdout="", returncode=2, stderr="unknown option")
            self.fail("Unexpected client command in tool preflight")

        with patch.object(remote.shutil, "which", return_value="/usr/bin/mysql"), \
             patch.object(remote.subprocess, "run", side_effect=client_result), \
             patch.object(remote, "identity", side_effect=AssertionError("Must stop before database connection")), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(remote.OpsError, "client options"):
                remote.prepare({"dev_password": "fixture", "test_password": "fixture"})

    def test_launcher_does_not_ask_database_passwords_if_tools_are_unavailable(self):
        class FailedOutput(io.StringIO):
            channel = types.SimpleNamespace(recv_exit_status=lambda: 1)

        outgoing = io.StringIO()
        outgoing.channel = types.SimpleNamespace(shutdown_write=lambda: None)
        incoming = FailedOutput('{"event":"failed","reason":"tool unavailable"}\n')
        client = types.SimpleNamespace(
            load_host_keys=lambda *args: None,
            set_missing_host_key_policy=lambda *args: None,
            connect=lambda *args, **kwargs: None,
            get_transport=lambda: types.SimpleNamespace(set_keepalive=lambda *args: None),
            exec_command=lambda *args, **kwargs: (outgoing, incoming, io.StringIO()),
            close=lambda: None,
        )
        prompts = []

        def password(label):
            prompts.append(label)
            if len(prompts) > 1:
                self.fail("Database password requested before client tools passed preflight")
            return "fixture"

        with tempfile.TemporaryDirectory() as directory, \
             patch.object(launcher.sys.stdin, "isatty", return_value=True), \
             patch.object(launcher.paramiko, "SSHClient", return_value=client), \
             patch.object(launcher, "hidden_password", side_effect=password), \
             patch.object(launcher, "private_output_directory", return_value=Path(directory)), \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, launcher.main())

    def test_wrapper_mysql_finds_dump_in_known_install_directory(self):
        # The mysql command can be a wrapper, not a symlink into its installation.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            wrapper = root / "wrapper" / "mysql"
            wrapper.parent.mkdir()
            wrapper.touch()
            install = root / "mysql8" / "bin"
            install.mkdir(parents=True)
            mysqldump = install / "mysqldump"
            mysqldump.touch()
            mysqldump.chmod(0o700)
            with patch.object(remote, "KNOWN_CLIENT_DIRS", (install,), create=True), \
                 patch.object(remote.shutil, "which", side_effect=lambda name: str(wrapper) if name == "mysql" else None), \
                 contextlib.redirect_stdout(io.StringIO()):
                try:
                    actual = remote.credentials_args(remote.DEV, "mysqldump")[0]
                except remote.OpsError as error:
                    self.fail(str(error))
                self.assertEqual(str(mysqldump.resolve()), actual)

    def test_mysql_sibling_dump_is_found_when_only_mysql_is_on_path(self):
        # A PATH-only lookup must not reject an existing executable beside mysql.
        with tempfile.TemporaryDirectory() as directory:
            mysql = Path(directory) / "mysql"
            mysqldump = Path(directory) / "mysqldump"
            mysql.touch()
            mysqldump.touch()
            mysql.chmod(0o700)
            mysqldump.chmod(0o700)
            versions = types.SimpleNamespace(stdout="MySQL Ver 8.0.46", returncode=0, stderr="")
            events = io.StringIO()
            with patch.object(remote.shutil, "which", side_effect=lambda name: str(mysql) if name == "mysql" else None), \
                 patch.object(remote.subprocess, "run", return_value=versions), \
                 patch.object(remote, "identity", return_value={"uuid": "same"}), \
                 patch.object(remote, "tables", return_value=[["existing", "BASE TABLE", "InnoDB"]]), \
                 contextlib.redirect_stdout(events):
                with self.assertRaisesRegex(remote.OpsError, "Test schema is not empty"):
                    remote.prepare({"dev_password": "fixture", "test_password": "fixture"})
                self.assertEqual(str(mysqldump.resolve()), remote.credentials_args(remote.DEV, "mysqldump")[0])

    def test_connection_builder_rejects_unrelated_database(self):
        for name in ("ecology", "mysql", "account_book", "other"):
            with self.subTest(name=name), self.assertRaises(remote.OpsError):
                remote.credentials_args(name)

    def test_identity_rejects_root_before_any_backup(self):
        with patch.object(remote, "query", return_value=[
                [remote.DEV, "root@localhost", "8.0.46", "uuid"]]):
            with self.assertRaisesRegex(remote.OpsError, "Wrong authenticated account"):
                remote.identity(remote.DEV, "fixture")

    def test_identity_rejects_selected_database_mismatch(self):
        with patch.object(remote, "query", return_value=[
                [remote.TEST, remote.DEV + "_app@127.0.0.1", "8.0.46", "uuid"]]):
            with self.assertRaisesRegex(remote.OpsError, "Wrong selected database"):
                remote.identity(remote.DEV, "fixture")

    def test_identity_accepts_expected_development_account(self):
        with patch.object(remote, "query", return_value=[
                [remote.DEV, remote.DEV + "_app@127.0.0.1", "8.0.46", "uuid"]]):
            result = remote.identity(remote.DEV, "fixture")
        self.assertEqual(remote.DEV, result["database"])
        self.assertEqual("uuid", result["uuid"])

    def test_existing_test_objects_stop_before_backup_or_restore(self):
        version = types.SimpleNamespace(stdout="mysql Ver 8.0.46", returncode=0, stderr="")
        with patch.object(remote.shutil, "which", return_value="/usr/bin/mysql"), \
             patch.object(remote.subprocess, "run", return_value=version), \
             patch.object(remote, "identity", return_value={"uuid": "same"}), \
             patch.object(remote, "tables", return_value=[["existing", "BASE TABLE", "InnoDB"]]), \
             patch.object(remote, "dump", side_effect=AssertionError("Backup must not start")), \
             patch.object(remote.tempfile, "mkdtemp", side_effect=AssertionError("No backup files yet")), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(remote.OpsError, "Test schema is not empty"):
                remote.prepare({"dev_password": "fixture", "test_password": "fixture"})

    def test_different_mysql_instances_stop_before_backup(self):
        version = types.SimpleNamespace(stdout="mysql Ver 8.0.46", returncode=0, stderr="")
        with patch.object(remote.shutil, "which", return_value="/usr/bin/mysql"), \
             patch.object(remote.subprocess, "run", return_value=version), \
             patch.object(remote, "identity", side_effect=[{"uuid": "one"}, {"uuid": "two"}]), \
             patch.object(remote, "dump", side_effect=AssertionError("Backup must not start")), \
             contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(remote.OpsError, "Database instances differ"):
                remote.prepare({"dev_password": "fixture", "test_password": "fixture"})

    def test_flyway_checksum_ignores_line_endings(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.sql"
            path.write_bytes(b"12345\r\n6789\n")
            self.assertEqual(-873187034, launcher.v1_checksum(path))

    def test_flyway_checksum_ignores_utf8_bom(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.sql"
            path.write_bytes(b"\xef\xbb\xbf123456789")
            self.assertEqual(-873187034, launcher.v1_checksum(path))


if __name__ == "__main__":
    unittest.main()
