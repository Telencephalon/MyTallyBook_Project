-- Read-only, lossless CHECK-clause collection after V2 was recorded successfully.
-- Execute in DBeaver using account_book_dev_app via the existing SSH tunnel.
-- Result rows are short hexadecimal chunks: no renderer quote escaping and no
-- manual guessing of truncated expressions. Copy ALL rows including part_no.
-- This SELECT does not change constraints, data, passwords or migration history.

WITH RECURSIVE check_chunks AS (
    SELECT constraint_name,
           HEX(check_clause) AS clause_hex,
           1 AS part_no
    FROM information_schema.check_constraints
    WHERE constraint_schema = 'account_book_dev'
      AND constraint_name IN (
          'ck_ledger_singleton',
          'ck_invite_status',
          'ck_invite_use_state'
      )

    UNION ALL

    SELECT constraint_name, clause_hex, part_no + 1
    FROM check_chunks
    WHERE part_no * 80 < CHAR_LENGTH(clause_hex)
)
SELECT constraint_name,
       part_no,
       CEILING(CHAR_LENGTH(clause_hex) / 80) AS total_parts,
       SUBSTRING(clause_hex, (part_no - 1) * 80 + 1, 80) AS text_hex
FROM check_chunks
ORDER BY constraint_name, part_no;
