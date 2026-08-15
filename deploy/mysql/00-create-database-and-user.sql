-- MyTallyBook production database bootstrap for MySQL 8.4.
-- Run once as a MySQL administrator on the Linux server.
-- CREATE USER intentionally has no IF NOT EXISTS: rerunning must not silently
-- replace or lose the generated application password.

SELECT VERSION() AS mysql_version,
       @@GLOBAL.character_set_server AS character_set_server,
       @@GLOBAL.collation_server AS collation_server,
       @@GLOBAL.lower_case_table_names AS lower_case_table_names;

CREATE DATABASE IF NOT EXISTS account_book
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

ALTER DATABASE account_book
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'account_book_app'@'127.0.0.1'
  IDENTIFIED BY RANDOM PASSWORD
  WITH MAX_USER_CONNECTIONS 20
  PASSWORD EXPIRE NEVER;

GRANT ALL PRIVILEGES ON account_book.*
  TO 'account_book_app'@'127.0.0.1';

SELECT SCHEMA_NAME,
       DEFAULT_CHARACTER_SET_NAME,
       DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'account_book';

SELECT user,
       host,
       plugin,
       account_locked,
       password_expired,
       max_user_connections
FROM mysql.user
WHERE user = 'account_book_app'
  AND host = '127.0.0.1';

SHOW GRANTS FOR 'account_book_app'@'127.0.0.1';
