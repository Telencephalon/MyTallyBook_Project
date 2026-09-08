-- MyTallyBook MySQL/Flyway read-only inspection script.
-- This script does not create, alter, update, or delete any database object.

SELECT VERSION() AS mysql_version;

SELECT @@hostname AS db_host,
       @@port AS db_port,
       @@character_set_server AS character_set_server,
       @@collation_server AS collation_server,
       @@transaction_isolation AS transaction_isolation,
       @@lower_case_table_names AS lower_case_table_names,
       @@max_connections AS max_connections,
       @@group_concat_max_len AS group_concat_max_len,
       @@log_bin_trust_function_creators AS log_bin_trust_function_creators,
       @@innodb_buffer_pool_size AS innodb_buffer_pool_size_bytes;

SELECT schema_name
FROM information_schema.schemata
WHERE schema_name IN ('account_book', 'account_book_dev', 'account_book_test')
ORDER BY schema_name;

SELECT table_schema,
       COUNT(*) AS application_table_count
FROM information_schema.tables
WHERE table_schema IN ('account_book', 'account_book_dev', 'account_book_test')
GROUP BY table_schema
ORDER BY table_schema;

SELECT table_schema,
       table_name
FROM information_schema.tables
WHERE table_schema IN ('account_book', 'account_book_dev', 'account_book_test')
  AND table_name = 'flyway_schema_history'
ORDER BY table_schema;

SET @has_prod_history = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = 'account_book'
      AND table_name = 'flyway_schema_history'
);
SET @prod_history_sql = IF(
    @has_prod_history > 0,
    'SELECT ''account_book'' AS environment_name, installed_rank, version, description, script, checksum, installed_on, success FROM account_book.flyway_schema_history ORDER BY installed_rank',
    'SELECT ''account_book'' AS environment_name, ''NO_FLYWAY_HISTORY'' AS state'
);
PREPARE prod_history_statement FROM @prod_history_sql;
EXECUTE prod_history_statement;
DEALLOCATE PREPARE prod_history_statement;

SET @has_dev_history = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = 'account_book_dev'
      AND table_name = 'flyway_schema_history'
);
SET @dev_history_sql = IF(
    @has_dev_history > 0,
    'SELECT ''account_book_dev'' AS environment_name, installed_rank, version, description, script, checksum, installed_on, success FROM account_book_dev.flyway_schema_history ORDER BY installed_rank',
    'SELECT ''account_book_dev'' AS environment_name, ''NO_FLYWAY_HISTORY'' AS state'
);
PREPARE dev_history_statement FROM @dev_history_sql;
EXECUTE dev_history_statement;
DEALLOCATE PREPARE dev_history_statement;

SET @has_test_history = (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = 'account_book_test'
      AND table_name = 'flyway_schema_history'
);
SET @test_history_sql = IF(
    @has_test_history > 0,
    'SELECT ''account_book_test'' AS environment_name, installed_rank, version, description, script, checksum, installed_on, success FROM account_book_test.flyway_schema_history ORDER BY installed_rank',
    'SELECT ''account_book_test'' AS environment_name, ''NO_FLYWAY_HISTORY'' AS state'
);
PREPARE test_history_statement FROM @test_history_sql;
EXECUTE test_history_statement;
DEALLOCATE PREPARE test_history_statement;
