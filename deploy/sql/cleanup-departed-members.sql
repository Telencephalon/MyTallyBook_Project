-- Prefer the companion Bash wrapper, which prompts for the MySQL password.
-- Direct use defaults to preview; set @cleanup_apply=1 explicitly to apply.
-- Only ledger_member associations are deleted. No app_user/book_entry changes.
SET @cleanup_apply = COALESCE(@cleanup_apply, 0);
SET @cleanup_expected_database = COALESCE(@cleanup_expected_database, 'account_book_dev');
SET @cleanup_ledger_id = COALESCE(@cleanup_ledger_id, 1);
SET SESSION sql_mode = CONCAT_WS(',', NULLIF(@@SESSION.sql_mode, ''), 'STRICT_ALL_TABLES');
SET SESSION innodb_lock_wait_timeout = 10;

DROP TEMPORARY TABLE IF EXISTS cleanup_departed_guard;
CREATE TEMPORARY TABLE cleanup_departed_guard (
    phase VARCHAR(16) NOT NULL PRIMARY KEY,
    safe_value TINYINT NOT NULL
) ENGINE=InnoDB;

-- NOT NULL plus strict mode fails before persistent mutations on unsafe state.
-- Inspect schema first: V2 must have removed the book_entry.member_id reference.
SET @cleanup_schema_ok = (
    DATABASE() = @cleanup_expected_database
    AND @cleanup_ledger_id = 1 AND @cleanup_apply IN (0, 1)
    AND (@cleanup_user_id IS NULL OR @cleanup_user_id > 0)
    AND @@SESSION.foreign_key_checks = 1
    AND (SELECT COUNT(*) FROM information_schema.tables
         WHERE table_schema = DATABASE() AND engine = 'InnoDB'
           AND table_name IN ('app_config','ledger','ledger_member','app_user','book_entry')) = 5
    AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'book_entry' AND column_name = 'member_id')
    AND EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ledger' AND column_name = 'singleton_key')
    AND NOT EXISTS (SELECT 1 FROM information_schema.key_column_usage
                    WHERE referenced_table_schema = DATABASE() AND referenced_table_name = 'ledger_member')
    AND NOT EXISTS (SELECT 1 FROM information_schema.triggers
                    WHERE event_object_schema = DATABASE() AND event_object_table = 'ledger_member')
);
SELECT 'schema_guard' AS stage, @cleanup_schema_ok AS passed;
INSERT INTO cleanup_departed_guard VALUES ('schema', IF(@cleanup_schema_ok, 1, NULL));

START TRANSACTION;
-- Same order as LedgerWriteGuard: configuration, ledger, then members/users.
SELECT id AS locked_config FROM app_config WHERE id = 1 FOR UPDATE;
SELECT id AS locked_ledger FROM ledger WHERE id = @cleanup_ledger_id FOR UPDATE;
SELECT lm.id AS locked_member, u.id AS locked_user
FROM ledger_member lm JOIN app_user u ON u.id = lm.user_id
WHERE lm.ledger_id = @cleanup_ledger_id ORDER BY lm.id FOR UPDATE;

SET @cleanup_state_ok = (
    @cleanup_schema_ok = 1
    AND (SELECT COUNT(*) FROM app_config WHERE id = 1 AND initialized = 1) = 1
    AND (SELECT COUNT(*) FROM ledger WHERE id = @cleanup_ledger_id AND status = 'ACTIVE') = 1
    AND (SELECT COUNT(*) FROM ledger_member lm JOIN app_user u ON u.id = lm.user_id
         WHERE lm.ledger_id = @cleanup_ledger_id AND lm.status = 'ACTIVE'
           AND lm.role = 'OWNER' AND u.status = 'ACTIVE') = 1
    AND (SELECT COUNT(*) FROM ledger l JOIN ledger_member lm ON lm.ledger_id = l.id
         JOIN app_user u ON u.id = lm.user_id
         WHERE l.id = @cleanup_ledger_id AND lm.user_id = l.owner_user_id
           AND lm.role = 'OWNER' AND lm.status = 'ACTIVE' AND u.status = 'ACTIVE') = 1
);
SELECT 'owner_and_state_guard' AS stage, @cleanup_state_ok AS passed;
INSERT INTO cleanup_departed_guard VALUES ('state', IF(@cleanup_state_ok, 1, NULL));

-- Current creator options use exactly these two ACTIVE predicates.
SELECT lm.id AS member_id, lm.user_id,
       COALESCE(NULLIF(lm.display_name, ''), u.nickname) AS creator_name
FROM ledger_member lm JOIN app_user u ON u.id = lm.user_id
WHERE lm.ledger_id = @cleanup_ledger_id AND lm.status = 'ACTIVE' AND u.status = 'ACTIVE'
ORDER BY lm.id;

-- Preview includes even soft-deleted historical bills; they are all retained.
SELECT lm.id AS departed_member_id, lm.user_id, u.nickname, lm.display_name,
       lm.status, lm.removed_at,
       (SELECT COUNT(*) FROM book_entry e
        WHERE e.ledger_id = lm.ledger_id AND e.created_by = lm.user_id) AS retained_bill_count
FROM ledger_member lm JOIN ledger l ON l.id = lm.ledger_id
LEFT JOIN app_user u ON u.id = lm.user_id
WHERE lm.ledger_id = @cleanup_ledger_id AND lm.status IN ('LEFT','REMOVED')
  AND lm.role <> 'OWNER' AND lm.user_id <> l.owner_user_id
  AND (@cleanup_user_id IS NULL OR lm.user_id = @cleanup_user_id)
ORDER BY lm.id;

SELECT COUNT(*) INTO @cleanup_users_before FROM app_user;
SELECT COUNT(*) INTO @cleanup_bills_before FROM book_entry;
SELECT COUNT(*) INTO @cleanup_active_before FROM ledger_member
WHERE ledger_id = @cleanup_ledger_id AND status = 'ACTIVE';

DELETE lm FROM ledger_member lm
JOIN ledger l ON l.id = lm.ledger_id
JOIN cleanup_departed_guard g ON g.phase = 'state' AND g.safe_value = 1
WHERE @cleanup_apply = 1 AND lm.ledger_id = @cleanup_ledger_id
  AND lm.status IN ('LEFT','REMOVED') AND lm.role <> 'OWNER'
  AND lm.user_id <> l.owner_user_id
  AND (@cleanup_user_id IS NULL OR lm.user_id = @cleanup_user_id);
SET @cleanup_deleted = ROW_COUNT();

SET @cleanup_result_ok = (
    @cleanup_state_ok = 1
    AND (SELECT COUNT(*) FROM app_user) = @cleanup_users_before
    AND (SELECT COUNT(*) FROM book_entry) = @cleanup_bills_before
    AND (SELECT COUNT(*) FROM ledger_member
         WHERE ledger_id = @cleanup_ledger_id AND status = 'ACTIVE') = @cleanup_active_before
);
SELECT 'retention_guard' AS stage, @cleanup_result_ok AS passed;
INSERT INTO cleanup_departed_guard VALUES ('result', IF(@cleanup_result_ok, 1, NULL));

-- COMMIT is preparable on MySQL 5.7/8.0; ROLLBACK need not be prepared.
-- Even a client incorrectly using --force cannot commit without all guards.
SET @cleanup_commit_ok = (@cleanup_apply = 1 AND
    (SELECT COUNT(*) FROM cleanup_departed_guard WHERE safe_value = 1) = 3);
SET @cleanup_finish = IF(@cleanup_commit_ok, 'COMMIT', 'DO 0');
PREPARE cleanup_finish_statement FROM @cleanup_finish;
EXECUTE cleanup_finish_statement;
DEALLOCATE PREPARE cleanup_finish_statement;
ROLLBACK;

SELECT IF(@cleanup_commit_ok, 'APPLIED', 'PREVIEW_ROLLED_BACK') AS result,
       @cleanup_deleted AS deleted_members, @cleanup_users_before AS retained_users,
       @cleanup_bills_before AS retained_bills;
DROP TEMPORARY TABLE cleanup_departed_guard;
