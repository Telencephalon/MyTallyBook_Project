import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path


SCRIPTS = Path(__file__).resolve().parents[1]


def load(filename):
    spec = importlib.util.spec_from_file_location("migration_launcher", SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixture():
    return {"status": "PREPARED_NOT_MIGRATED", "restore_verified": True,
            "download_verified": True, "test_schema_empty": True, "development_migrated": False,
            "identity": {"database": "account_book_dev", "current_user": "account_book_dev_app@127.0.0.1",
                         "uuid": "20f56ed0-988d-11f1-aeb3-fa163ed2bb15", "version": "8.0.46"},
            "backup": "/var/backups/mytallybook-dev-v2-3ni425zj/account_book_dev-v1.sql",
            "sha256": "d24dbef7d6fbb39ca0bd3edaf6ace687d2a3901e5209cfcab17b76587e7d13e7",
            "bytes": 15284, "v1_checksum": 6679958}


class MigrationSafetyTests(unittest.TestCase):
    def test_rejects_backup_without_restore_evidence(self):
        module = load("run-dev-v2.py")
        record = fixture()
        record["restore_verified"] = False
        with self.assertRaisesRegex(ValueError, "verified backup"):
            module.validate_backup_metadata(record)

    def test_rejects_wrong_database_identity(self):
        module = load("run-dev-v2.py")
        record = fixture()
        record["identity"]["database"] = "ecology"
        with self.assertRaisesRegex(ValueError, "identity"):
            module.validate_backup_metadata(record)

    def test_rejects_unexpected_backup_path(self):
        module = load("run-dev-v2.py")
        record = fixture()
        record["backup"] = "/var/backups/other.sql"
        with self.assertRaisesRegex(ValueError, "path"):
            module.validate_backup_metadata(record)

    def test_accepts_independently_verified_backup(self):
        module = load("run-dev-v2.py")
        record = fixture()
        module.validate_backup_metadata(record)

    def test_maintenance_acknowledgement_requires_exact_migrate(self):
        module = load("run-dev-v2.py")
        self.assertTrue(hasattr(module, "validate_maintenance_acknowledgement"))
        module.validate_maintenance_acknowledgement("MIGRATE")
        for response in ("", "migrate", " MIGRATE", "MIGRATE "):
            with self.subTest(response=response):
                with self.assertRaisesRegex(ValueError, "maintenance"):
                    module.validate_maintenance_acknowledgement(response)

    def test_rejects_any_mutation_of_the_independently_pinned_v2(self):
        module = load("run-dev-v2.py")
        self.assertTrue(hasattr(module, "validate_v2_fingerprint"))
        approved = SCRIPTS.parents[1] / "account-book-server/src/main/resources/db/migration/V2__align_approved_design.sql"
        module.validate_v2_fingerprint(approved)
        with tempfile.TemporaryDirectory() as directory:
            mutated = Path(directory) / approved.name
            mutated.write_bytes(approved.read_bytes() + b"\n-- unapproved mutation\n")
            with self.assertRaisesRegex(ValueError, "V2 fingerprint"):
                module.validate_v2_fingerprint(mutated)

    def test_timeout_retains_partial_events_and_records_ambiguous_stop(self):
        module = load("run-dev-v2.py")
        self.assertTrue(hasattr(module, "run_java_and_capture"))
        partial = (b'V2_EVENT {"event":"identity_verified"}\n'
                   b'V2_EVENT {"event":"migration_started","target":"2"}\n')

        def timeout(*args, **kwargs):
            raise subprocess.TimeoutExpired(args[0], 420, output=partial)

        log = io.StringIO()
        console = io.StringIO()
        with redirect_stdout(console):
            complete = module.run_java_and_capture(
                ["java", "DevV2Migration"], {}, "credential-payload", log, run=timeout
            )
        self.assertIsNone(complete)
        events = [json.loads(line) for line in log.getvalue().splitlines()]
        self.assertEqual(["identity_verified", "migration_started", "migration_stopped"],
                         [event["event"] for event in events])
        self.assertEqual(True, events[-1]["ambiguous_state"])
        self.assertEqual(False, events[-1]["automatic_retry"])
        self.assertEqual(True, events[-1]["backup_retained"])
        self.assertEqual(True, events[-1]["approved_recovery_required"])
        self.assertIn("Do not retry", console.getvalue())
        self.assertIn("approved recovery", console.getvalue())
        self.assertNotIn("stopped safely", console.getvalue())
        self.assertNotIn("credential-payload", console.getvalue() + log.getvalue())

    def test_malformed_event_retains_other_events_and_records_ambiguous_stop(self):
        module = load("run-dev-v2.py")
        self.assertTrue(hasattr(module, "run_java_and_capture"))

        def malformed(*args, **kwargs):
            return subprocess.CompletedProcess(
                args[0],
                0,
                stdout=('V2_EVENT {"event":"identity_verified"}\n'
                        'V2_EVENT {not-json}\n'
                        'V2_EVENT {"event":"migration_complete","versions":["1","2"],'
                        '"migrations_executed":1,"v1_checksum":1,"v2_checksum":2}\n'),
                stderr="third-party diagnostics"
            )

        log = io.StringIO()
        console = io.StringIO()
        with redirect_stdout(console):
            complete = module.run_java_and_capture(
                ["java", "DevV2Migration"], {}, "credential-payload", log, run=malformed
            )
        self.assertIsNone(complete)
        events = [json.loads(line) for line in log.getvalue().splitlines()]
        self.assertEqual(["identity_verified", "migration_complete", "migration_stopped"],
                         [event["event"] for event in events])
        self.assertEqual("MalformedRunnerEvent", events[-1]["failure_type"])
        self.assertIn("Do not retry", console.getvalue())
        self.assertNotIn("third-party diagnostics", console.getvalue() + log.getvalue())


if __name__ == "__main__":
    unittest.main()
