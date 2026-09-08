-- Align the immutable V1 schema with the approved fixed-ledger design.
--
-- V1 has already been applied to development databases and must never be
-- edited. This migration is deliberately safe to run both after an existing
-- V1 and when Flyway builds a new database from V1 followed by V2.

-- A legacy database with multiple ledgers (or a non-standard fixed ID) cannot
-- be mapped to the approved fixed ledger automatically. Consumed invitations
-- also cannot be mapped to used_by because V1 did not record the consumer.
-- Fail before changing persistent schema so an operator can inspect the data.
-- A temporary table makes the guard safe to
-- stop before any persistent schema change. An operator must then inspect the
-- legacy data and approve an explicit data-migration decision; this migration
-- must not be forced through with Flyway repair.
DROP TEMPORARY TABLE IF EXISTS flyway_v2_state_guard;

CREATE TEMPORARY TABLE flyway_v2_state_guard (
    safe_value TINYINT UNSIGNED NOT NULL,
    CONSTRAINT ck_v2_legacy_state_is_safe CHECK (safe_value = 1)
) ENGINE=InnoDB;

INSERT INTO flyway_v2_state_guard (safe_value)
SELECT CASE
           WHEN (SELECT COUNT(*) FROM ledger) > 1
             OR EXISTS (
               SELECT 1
               FROM ledger
               WHERE id <> 1
           )
             OR EXISTS (
               SELECT 1
               FROM ledger_invite
               WHERE used_count <> 0
                  OR status NOT IN ('ACTIVE', 'REVOKED')
           ) THEN 0
           ELSE 1
       END;

DROP TEMPORARY TABLE flyway_v2_state_guard;

-- Keep V1's identifier behavior intact and enforce the singleton with a
-- constant, checked unique key. The precondition above has already established
-- that any existing ledger is the single fixed row with ID 1.
ALTER TABLE ledger
    ADD COLUMN singleton_key TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER id,
    ADD UNIQUE KEY uk_ledger_singleton (singleton_key),
    ADD CONSTRAINT ck_ledger_singleton CHECK (singleton_key = 1);

-- Invitations are single-use. Only ACTIVE and REVOKED rows can reach this
-- point, so adding the new state contract cannot discard consumption data.
ALTER TABLE ledger_invite
    DROP CHECK ck_invite_uses,
    DROP CHECK ck_invite_status,
    ADD COLUMN used_by BIGINT UNSIGNED NULL AFTER created_by,
    ADD COLUMN used_at DATETIME(3) NULL AFTER used_by,
    DROP COLUMN max_uses,
    DROP COLUMN used_count,
    ADD CONSTRAINT fk_invite_consumer
        FOREIGN KEY (used_by) REFERENCES app_user(id),
    ADD CONSTRAINT ck_invite_status
        CHECK (status IN ('ACTIVE','USED','REVOKED','EXPIRED')),
    ADD CONSTRAINT ck_invite_use_state CHECK (
        (status = 'USED' AND used_by IS NOT NULL AND used_at IS NOT NULL)
        OR
        (status <> 'USED' AND used_by IS NULL AND used_at IS NULL)
    );

-- The authenticated creator is the only member dimension used by the
-- approved statistics contract. created_by is already NOT NULL in V1.
ALTER TABLE book_entry
    DROP FOREIGN KEY fk_entry_member,
    DROP INDEX idx_entry_member_date,
    DROP COLUMN member_id,
    ADD INDEX idx_entry_creator_date (ledger_id, created_by, entry_date);

-- X-Request-Id accepts 1..64 safe ASCII characters. Widening CHAR(36) is
-- non-destructive and keeps binary/case-sensitive request-id semantics.
ALTER TABLE audit_log
    MODIFY COLUMN request_id
        VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
