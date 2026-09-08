-- Read-only diagnosis after the 2026-09-05 development V2 attempt.
-- Run in the saved account_book_dev_app connection through 127.0.0.1:13306.
-- SELECT only: do not restart the migration or application while inspecting.
-- Expected identity: account_book_dev / account_book_dev_app@127.0.0.1
-- Expected server UUID: 20f56ed0-988d-11f1-aeb3-fa163ed2bb15

-- 1. Verify the connection first. Stop if identity/UUID differs.
SELECT DATABASE() AS database_name,
       CURRENT_USER() AS authenticated_account,
       VERSION() AS mysql_version,
       @@server_uuid AS server_uuid;

-- 2. Inspect actual persisted migration history.
SELECT installed_rank, version, description, type, script, checksum,
       installed_on, execution_time, success
FROM account_book_dev.flyway_schema_history
ORDER BY installed_rank;

-- 3. Inspect complete CHECK expressions, not only constraint names.
SELECT tc.table_name, tc.constraint_name, tc.enforced, cc.check_clause
FROM information_schema.table_constraints AS tc
JOIN information_schema.check_constraints AS cc
  ON cc.constraint_schema = tc.constraint_schema
 AND cc.constraint_name = tc.constraint_name
WHERE tc.constraint_schema = 'account_book_dev'
  AND tc.constraint_type = 'CHECK'
  AND tc.table_name IN ('ledger', 'ledger_invite')
ORDER BY tc.table_name, tc.constraint_name;

-- 4. Inspect old and new columns affected by V2.
SELECT table_name, column_name, column_type, is_nullable, column_default,
       character_maximum_length, character_set_name, collation_name,
       datetime_precision
FROM information_schema.columns
WHERE table_schema = 'account_book_dev'
  AND (
    (table_name = 'ledger' AND column_name IN ('id', 'singleton_key'))
    OR (table_name = 'ledger_invite'
        AND column_name IN ('status', 'used_by', 'used_at', 'max_uses', 'used_count'))
    OR (table_name = 'book_entry' AND column_name IN ('created_by', 'member_id'))
    OR (table_name = 'audit_log' AND column_name = 'request_id')
  )
ORDER BY table_name, ordinal_position;

-- 5. Inspect precise index shapes.
SELECT table_name, index_name, non_unique, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'account_book_dev'
  AND (
    (table_name = 'ledger' AND index_name = 'uk_ledger_singleton')
    OR (table_name = 'book_entry'
        AND index_name IN ('idx_entry_member_date', 'idx_entry_creator_date'))
  )
ORDER BY table_name, index_name, seq_in_index;

-- 6. Inspect old/new foreign keys.
SELECT table_name, constraint_name, column_name, referenced_table_schema,
       referenced_table_name, referenced_column_name
FROM information_schema.key_column_usage
WHERE constraint_schema = 'account_book_dev'
  AND referenced_table_name IS NOT NULL
  AND constraint_name IN ('fk_invite_consumer', 'fk_entry_member')
ORDER BY table_name, constraint_name, ordinal_position;

-- 7. Verify the fixed object inventory.
SELECT table_name, table_type, engine
FROM information_schema.tables
WHERE table_schema = 'account_book_dev'
ORDER BY table_name;

-- 8. Counts only; no user, token, or configuration values are exposed.
-- Before migration app_config=1, all other business tables=0, history=1.
SELECT 'app_config' AS table_name, COUNT(*) AS row_count FROM account_book_dev.app_config
UNION ALL SELECT 'app_user', COUNT(*) FROM account_book_dev.app_user
UNION ALL SELECT 'audit_log', COUNT(*) FROM account_book_dev.audit_log
UNION ALL SELECT 'auth_session', COUNT(*) FROM account_book_dev.auth_session
UNION ALL SELECT 'book_entry', COUNT(*) FROM account_book_dev.book_entry
UNION ALL SELECT 'category', COUNT(*) FROM account_book_dev.category
UNION ALL SELECT 'fund_account', COUNT(*) FROM account_book_dev.fund_account
UNION ALL SELECT 'ledger', COUNT(*) FROM account_book_dev.ledger
UNION ALL SELECT 'ledger_invite', COUNT(*) FROM account_book_dev.ledger_invite
UNION ALL SELECT 'ledger_member', COUNT(*) FROM account_book_dev.ledger_member
UNION ALL SELECT 'flyway_schema_history', COUNT(*) FROM account_book_dev.flyway_schema_history;

-- 9. Observe, never acquire or release, the cooperative migration lock.
SELECT IS_FREE_LOCK('mytallybook_dev_v2_upgrade') AS migration_lock_is_free;
