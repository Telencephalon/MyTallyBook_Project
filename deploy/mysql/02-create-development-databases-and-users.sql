-- MyTallyBook development and automated-test database bootstrap for MySQL 8.x.
-- Run once as a MySQL administrator in a private terminal.
-- CREATE USER returns generated passwords. Store them in an ignored local
-- secret file or a password manager; never paste them into Git, Markdown,
-- screenshots, build logs, or chat.
-- account_book_test is a dedicated disposable-schema database. Automated tests
-- may run Flyway clean against objects inside this exact database only after
-- DB_TEST_RESET_ALLOWED=account_book_test is set. Never store manual or shared
-- data in it; the test harness never drops the database itself.
--
-- CREATE USER intentionally has no IF NOT EXISTS. A repeated execution must
-- stop instead of silently changing or hiding an existing credential state.

CREATE DATABASE IF NOT EXISTS account_book_dev
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

ALTER DATABASE account_book_dev
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS account_book_test
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

ALTER DATABASE account_book_test
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'account_book_dev_app'@'127.0.0.1'
  IDENTIFIED BY RANDOM PASSWORD
  WITH MAX_USER_CONNECTIONS 10
  PASSWORD EXPIRE NEVER;

CREATE USER 'account_book_test_app'@'127.0.0.1'
  IDENTIFIED BY RANDOM PASSWORD
  WITH MAX_USER_CONNECTIONS 10
  PASSWORD EXPIRE NEVER;

GRANT ALL PRIVILEGES ON account_book_dev.*
  TO 'account_book_dev_app'@'127.0.0.1';

GRANT ALL PRIVILEGES ON account_book_test.*
  TO 'account_book_test_app'@'127.0.0.1';

SELECT schema_name,
       default_character_set_name,
       default_collation_name
FROM information_schema.schemata
WHERE schema_name IN ('account_book_dev', 'account_book_test')
ORDER BY schema_name;

SELECT user,
       host,
       plugin,
       account_locked,
       password_expired,
       max_user_connections
FROM mysql.user
WHERE user IN ('account_book_dev_app', 'account_book_test_app')
  AND host = '127.0.0.1'
ORDER BY user;

SHOW GRANTS FOR 'account_book_dev_app'@'127.0.0.1';
SHOW GRANTS FOR 'account_book_test_app'@'127.0.0.1';
