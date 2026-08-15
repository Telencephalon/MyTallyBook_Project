CREATE TABLE app_config (
    id              TINYINT UNSIGNED NOT NULL,
    initialized     BOOLEAN NOT NULL DEFAULT FALSE,
    max_users       TINYINT UNSIGNED NOT NULL DEFAULT 10,
    version         INT UNSIGNED NOT NULL DEFAULT 0,
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT ck_app_config_singleton CHECK (id = 1),
    CONSTRAINT ck_app_config_max_users CHECK (max_users BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO app_config (id, initialized, max_users)
VALUES (1, FALSE, 10);

CREATE TABLE app_user (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    openid          VARCHAR(64) NOT NULL,
    unionid         VARCHAR(64) NULL,
    nickname        VARCHAR(64) NOT NULL DEFAULT '微信用户',
    avatar_url      VARCHAR(512) NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_login_at   DATETIME(3) NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
    version         INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_openid (openid),
    KEY idx_app_user_unionid (unionid),
    KEY idx_app_user_status (status),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE','DISABLED','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_session (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id         BIGINT UNSIGNED NOT NULL,
    token_hash      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at      DATETIME(3) NOT NULL,
    revoked_at      DATETIME(3) NULL,
    last_seen_at    DATETIME(3) NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_session_token_hash (token_hash),
    KEY idx_auth_session_user (user_id, revoked_at),
    KEY idx_auth_session_expiry (expires_at),
    CONSTRAINT fk_auth_session_user
        FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ledger (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name            VARCHAR(64) NOT NULL,
    currency        CHAR(3) CHARACTER SET ascii NOT NULL DEFAULT 'CNY',
    timezone        VARCHAR(40) CHARACTER SET ascii NOT NULL DEFAULT 'Asia/Shanghai',
    owner_user_id   BIGINT UNSIGNED NOT NULL,
    max_members     TINYINT UNSIGNED NOT NULL DEFAULT 10,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
    version         INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_ledger_owner (owner_user_id),
    CONSTRAINT fk_ledger_owner
        FOREIGN KEY (owner_user_id) REFERENCES app_user(id),
    CONSTRAINT ck_ledger_max_members CHECK (max_members BETWEEN 1 AND 10),
    CONSTRAINT ck_ledger_status CHECK (status IN ('ACTIVE','ARCHIVED','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ledger_member (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ledger_id       BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    role            VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    display_name    VARCHAR(64) NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    joined_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    removed_at      DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_member (ledger_id, user_id),
    KEY idx_member_user (user_id, status),
    CONSTRAINT fk_member_ledger
        FOREIGN KEY (ledger_id) REFERENCES ledger(id),
    CONSTRAINT fk_member_user
        FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT ck_member_role CHECK (role IN ('OWNER','ADMIN','MEMBER')),
    CONSTRAINT ck_member_status CHECK (status IN ('ACTIVE','REMOVED','LEFT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ledger_invite (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ledger_id       BIGINT UNSIGNED NOT NULL,
    token_hash      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by      BIGINT UNSIGNED NOT NULL,
    max_uses        TINYINT UNSIGNED NOT NULL DEFAULT 1,
    used_count      TINYINT UNSIGNED NOT NULL DEFAULT 0,
    expires_at      DATETIME(3) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_invite_token_hash (token_hash),
    KEY idx_invite_ledger (ledger_id, status, expires_at),
    CONSTRAINT fk_invite_ledger
        FOREIGN KEY (ledger_id) REFERENCES ledger(id),
    CONSTRAINT fk_invite_creator
        FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT ck_invite_uses
        CHECK (max_uses BETWEEN 1 AND 9 AND used_count <= max_uses),
    CONSTRAINT ck_invite_status CHECK (status IN ('ACTIVE','REVOKED','EXHAUSTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE category (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ledger_id       BIGINT UNSIGNED NOT NULL,
    entry_type      VARCHAR(16) NOT NULL,
    name            VARCHAR(40) NOT NULL,
    icon            VARCHAR(40) NULL,
    color           CHAR(7) CHARACTER SET ascii NULL,
    sort_no         SMALLINT NOT NULL DEFAULT 0,
    system_default  BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                    ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_name (ledger_id, entry_type, name),
    KEY idx_category_list (ledger_id, entry_type, status, sort_no),
    CONSTRAINT fk_category_ledger
        FOREIGN KEY (ledger_id) REFERENCES ledger(id),
    CONSTRAINT ck_category_type CHECK (entry_type IN ('INCOME','EXPENSE')),
    CONSTRAINT ck_category_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE fund_account (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ledger_id         BIGINT UNSIGNED NOT NULL,
    name              VARCHAR(40) NOT NULL,
    account_type      VARCHAR(20) NOT NULL,
    initial_balance   DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    sort_no           SMALLINT NOT NULL DEFAULT 0,
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                      ON UPDATE CURRENT_TIMESTAMP(3),
    version           INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_account_name (ledger_id, name),
    KEY idx_fund_account_list (ledger_id, status, sort_no),
    CONSTRAINT fk_fund_account_ledger
        FOREIGN KEY (ledger_id) REFERENCES ledger(id),
    CONSTRAINT ck_fund_account_type
        CHECK (account_type IN ('CASH','WECHAT','BANK','ALIPAY','OTHER')),
    CONSTRAINT ck_fund_account_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE book_entry (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ledger_id           BIGINT UNSIGNED NOT NULL,
    entry_type          VARCHAR(16) NOT NULL,
    amount              DECIMAL(15,2) NOT NULL,
    category_id         BIGINT UNSIGNED NOT NULL,
    account_id          BIGINT UNSIGNED NOT NULL,
    member_id           BIGINT UNSIGNED NOT NULL,
    entry_date          DATE NOT NULL,
    note                VARCHAR(500) NULL,
    client_request_id   CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by          BIGINT UNSIGNED NOT NULL,
    updated_by          BIGINT UNSIGNED NOT NULL,
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                        ON UPDATE CURRENT_TIMESTAMP(3),
    deleted_at          DATETIME(3) NULL,
    version             INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entry_idempotency (ledger_id, client_request_id),
    KEY idx_entry_ledger_date (ledger_id, entry_date DESC, id DESC),
    KEY idx_entry_category_date (ledger_id, category_id, entry_date),
    KEY idx_entry_account_date (ledger_id, account_id, entry_date),
    KEY idx_entry_member_date (ledger_id, member_id, entry_date),
    CONSTRAINT fk_entry_ledger
        FOREIGN KEY (ledger_id) REFERENCES ledger(id),
    CONSTRAINT fk_entry_category
        FOREIGN KEY (category_id) REFERENCES category(id),
    CONSTRAINT fk_entry_account
        FOREIGN KEY (account_id) REFERENCES fund_account(id),
    CONSTRAINT fk_entry_member
        FOREIGN KEY (member_id) REFERENCES ledger_member(id),
    CONSTRAINT fk_entry_creator
        FOREIGN KEY (created_by) REFERENCES app_user(id),
    CONSTRAINT fk_entry_updater
        FOREIGN KEY (updated_by) REFERENCES app_user(id),
    CONSTRAINT ck_entry_type CHECK (entry_type IN ('INCOME','EXPENSE')),
    CONSTRAINT ck_entry_amount CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ledger_id       BIGINT UNSIGNED NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    action          VARCHAR(50) NOT NULL,
    resource_type   VARCHAR(30) NOT NULL,
    resource_id     BIGINT UNSIGNED NULL,
    request_id      CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    details_json    JSON NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_audit_ledger_time (ledger_id, created_at DESC),
    KEY idx_audit_user_time (user_id, created_at DESC),
    CONSTRAINT fk_audit_ledger
        FOREIGN KEY (ledger_id) REFERENCES ledger(id),
    CONSTRAINT fk_audit_user
        FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
