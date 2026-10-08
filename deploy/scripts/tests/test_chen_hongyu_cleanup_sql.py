"""Run the Chen Hongyu shell script's embedded SQL on a disposable MySQL fixture.

Python standard library only; accepts local binaries, never an existing server:
  python deploy/scripts/tests/test_chen_hongyu_cleanup_sql.py --mysql-bin "PATH/bin"

The existing LocalMySQL runner starts and verifies its own loopback child server.
Fixture datadir, command outputs, SQL hash and snapshot hashes remain in target.
"""

import argparse
import hashlib
import json
from pathlib import Path
import sys
import unittest

import test_cleanup_departed_members_sql as fixture


SCRIPT_FILE = fixture.ROOT / "deploy/scripts/cleanup-chen-hongyu-member.sh"
SQL_MARKER = "CHEN_CLEANUP_SQL"
TARGET_NAME = "陈泓宇"
ALL_MEMBER_IDS = [101, 102, 103, 104, 105, 106, 107, 201, 202, 203]


def embedded_sql():
    if not SCRIPT_FILE.is_file() or not SCRIPT_FILE.stat().st_size:
        raise AssertionError("Chen Hongyu cleanup script missing or empty: " + str(SCRIPT_FILE))
    source = SCRIPT_FILE.read_text(encoding="utf-8-sig")
    opener = "<<'" + SQL_MARKER + "'"
    if source.count(opener) != 1:
        raise AssertionError("Expected exactly one quoted SQL heredoc: " + opener)
    remainder = source.split(opener, 1)[1]
    body = remainder.split("\n", 1)[1]
    lines = body.splitlines(keepends=True)
    closing = [index for index, line in enumerate(lines) if line.rstrip("\r\n") == SQL_MARKER]
    if len(closing) != 1:
        raise AssertionError("Expected exactly one embedded SQL closing marker line")
    sql = "".join(lines[:closing[0]])
    if not sql.strip():
        raise AssertionError("Embedded cleanup SQL is empty")
    return source, sql + "\n"


class ChenHongyuCleanupSQLTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source, cls.sql = embedded_sql()
        cls.mysql = fixture.LocalMySQL()
        cls.addClassCleanup(cls.mysql.stop)
        cls.mysql.start()
        cls.evidence = {
            "script": str(SCRIPT_FILE),
            "script_sha256": hashlib.sha256(source.encode("utf-8")).hexdigest(),
            "embedded_sql_sha256": hashlib.sha256(cls.sql.encode("utf-8")).hexdigest(),
            "mysql_version": cls.mysql.execute("SELECT VERSION();", database=None).splitlines()[-1],
            "fixture_datadir": str(cls.mysql.datadir),
            "observations": [],
        }
        cls.addClassCleanup(cls.save_evidence)

    @classmethod
    def save_evidence(cls):
        path = cls.mysql.directory / "chen-hongyu-test-evidence.json"
        path.write_text(json.dumps(cls.evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Chen Hongyu test evidence: " + str(path), file=sys.stderr, flush=True)

    def setUp(self):
        self.mysql.reset()
        self.mysql.execute("UPDATE app_user SET nickname='陈泓宇' WHERE id=3;")

    def cleanup(self, apply=None, force=False):
        settings = "" if apply is None else "SET @cleanup_apply=%d;\n" % apply
        return self.mysql.run(settings + self.sql, force=force)

    def snapshot(self, label):
        values = self.mysql.snapshot()
        digest = hashlib.sha256(json.dumps(values, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()
        self.evidence["observations"].append({
            "test": self.id(), "label": label, "snapshot_sha256": digest,
            "subtest": str(self._subtest) if self._subtest is not None else None,
            "table_sha256": {table: hashlib.sha256(rows.encode("utf-8")).hexdigest()
                             for table, rows in sorted(values.items())},
        })
        return values

    def assert_success(self, result, deleted, preview=False):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("ERROR", result.stderr)
        expected_status = "PREVIEW_ROLLED_BACK" if preview else "APPLIED"
        self.assertEqual([[expected_status, str(deleted), "11", "9"]],
                         fixture.result_rows(result.stdout, "result"))

    def assert_only_target_membership_removed(self, before):
        after = self.snapshot("after_apply")
        self.assertEqual(set(before), set(after))
        for table, rows in before.items():
            if table != "ledger_member":
                self.assertEqual(rows, after[table], table + " was changed")
        expected = "".join(row + "\n" for row in before["ledger_member"].splitlines()
                           if int(row.split("\t")[0]) != 103)
        self.assertEqual(expected, after["ledger_member"], "unexpected membership change")
        self.assertEqual([member_id for member_id in ALL_MEMBER_IDS if member_id != 103],
                         [int(row.split("\t")[0]) for row in after["ledger_member"].splitlines()])

    def assert_rejected_without_mutation(self, mutation, force=False):
        self.mysql.execute(mutation)
        before = self.snapshot("before_rejected_apply")
        result = self.cleanup(apply=1, force=force)
        if not force:
            self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("ERROR", result.stderr)
        self.assertEqual(before, self.snapshot("after_rejected_apply"))

    def test_default_preview_preserves_all_tables(self):
        before = self.snapshot("before_preview")
        result = self.cleanup()
        self.assert_success(result, deleted=0, preview=True)
        candidates = fixture.result_rows(result.stdout, "departed_member_id")
        self.assertEqual([103], [int(row[0]) for row in candidates])
        self.assertEqual(2, int(candidates[0][-1]), "historical and soft-deleted bills count")
        self.assertEqual(before, self.snapshot("after_preview"))

    def test_apply_removes_only_target_for_nickname_alias_or_both(self):
        mutations = (
            "SELECT 1;",
            "UPDATE app_user SET nickname='other nickname' WHERE id=3; "
            "UPDATE ledger_member SET display_name='陈泓宇' WHERE id=103;",
            "UPDATE ledger_member SET display_name='陈泓宇' WHERE id=103;",
            "UPDATE ledger_member SET status='REMOVED' WHERE id=103;",
            "UPDATE app_user SET nickname='陈泓宇' WHERE id=10;",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.setUp()
                self.mysql.execute(mutation)
                before = self.snapshot("before_apply")
                result = self.cleanup(apply=1)
                self.assert_success(result, deleted=1)
                self.assertEqual([103], [int(row[0]) for row in
                                        fixture.result_rows(result.stdout, "departed_member_id")])
                self.assert_only_target_membership_removed(before)

    def test_repeated_apply_is_noop(self):
        self.assert_success(self.cleanup(apply=1), deleted=1)
        before = self.snapshot("before_repeat")
        self.assert_success(self.cleanup(apply=1), deleted=0)
        self.assertEqual(before, self.snapshot("after_repeat"))

    def test_no_match_and_partial_names_are_noops(self):
        for name in ("unrelated", "陈泓宇a", "a陈泓宇", "陈泓宇 "):
            with self.subTest(name=name):
                self.mysql.execute("UPDATE app_user SET nickname='%s' WHERE id=3; "
                                   "UPDATE ledger_member SET display_name='%s' WHERE id=103;" % (name, name))
                before = self.snapshot("before_no_match")
                result = self.cleanup(apply=1)
                self.assert_success(result, deleted=0)
                # mysql --batch omits the header of an empty result set.
                self.assertNotIn("departed_member_id\t", result.stdout)
                lines = result.stdout.splitlines()
                report_index = lines.index("target_name\tmatched_members\tresolved_user_id\tname_check")
                self.assertEqual([TARGET_NAME, "0", "0", "NO_ASSOCIATION_FOUND_OR_ALREADY_CLEANED"],
                                 lines[report_index + 1].split("\t"))
                self.assertEqual(before, self.snapshot("after_no_match"))

    def test_same_name_active_and_left_members_are_rejected(self):
        for force in (False, True):
            with self.subTest(force=force):
                self.setUp()
                self.assert_rejected_without_mutation(
                    "UPDATE app_user SET nickname='陈泓宇' WHERE id=2;", force=force)

    def test_unique_active_match_is_rejected(self):
        self.assert_rejected_without_mutation("UPDATE ledger_member SET status='ACTIVE' WHERE id=103;")

    def test_owner_matches_are_rejected(self):
        mutations = (
            "UPDATE ledger_member SET role='OWNER' WHERE id=103;",
            "UPDATE app_user SET nickname='unrelated' WHERE id=3; "
            "UPDATE app_user SET nickname='陈泓宇' WHERE id=1;",
            "UPDATE app_user SET nickname='unrelated' WHERE id=3; "
            "UPDATE app_user SET nickname='陈泓宇' WHERE id=6;",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.setUp()
                self.assert_rejected_without_mutation(mutation)

    def test_schema_and_state_guards_reject_without_changes(self):
        mutations = (
            "UPDATE app_config SET initialized=0 WHERE id=1;",
            "ALTER TABLE book_entry ADD member_id BIGINT UNSIGNED NULL;",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.setUp()
                self.assert_rejected_without_mutation(mutation, force=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mysql-bin", type=Path, help="directory containing local mysql and mysqld binaries")
    options, unittest_arguments = parser.parse_known_args()
    fixture.MYSQL_BIN = options.mysql_bin
    unittest.main(argv=[sys.argv[0]] + unittest_arguments, verbosity=2)
