--liquibase formatted sql

-- changeset jovan:10
-- PR_03 C3.2 + PR_15: kreiranje margin_accounts hijerarhije (joined inheritance).
-- Spec: Marzni_Racuni.txt. IF NOT EXISTS radi idempotencije pri partial-apply slucajevima
-- (banking-core konsolidacija je morala vise puta da pokrene Liquibase nakon failed migracija).

CREATE TABLE IF NOT EXISTS margin_accounts (
    id                 BIGSERIAL    PRIMARY KEY,
    initial_margin     NUMERIC(19,2) NOT NULL CHECK (initial_margin >= 0),
    loan_value         NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (loan_value >= 0),
    maintenance_margin NUMERIC(19,2) NOT NULL CHECK (maintenance_margin >= 0),
    bank_participation NUMERIC(5,4)  NOT NULL CHECK (bank_participation >= 0 AND bank_participation <= 1),
    account_number     VARCHAR(16)   NOT NULL UNIQUE CHECK (account_number ~ '^[0-9]{16}$'),
    currency           VARCHAR(3)    NOT NULL DEFAULT 'RSD',
    active             BOOLEAN       NOT NULL DEFAULT true,
    deleted            BOOLEAN       NOT NULL DEFAULT false,
    owner_kind         VARCHAR(16)   NOT NULL,  -- discriminator: USER | COMPANY
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP,
    version            BIGINT        NOT NULL DEFAULT 0  -- @Version optimistic lock
);

CREATE INDEX IF NOT EXISTS idx_margin_accounts_owner_kind ON margin_accounts(owner_kind);
CREATE INDEX IF NOT EXISTS idx_margin_accounts_active     ON margin_accounts(active) WHERE deleted = false;

-- rollback DROP INDEX IF EXISTS idx_margin_accounts_active;
-- rollback DROP INDEX IF EXISTS idx_margin_accounts_owner_kind;
-- rollback DROP TABLE IF EXISTS margin_accounts;


-- changeset jovan:11
-- PR_03 C3.2: user_margin_accounts (FK ka margin_accounts.id; user_id unique).

CREATE TABLE IF NOT EXISTS user_margin_accounts (
    id      BIGINT NOT NULL PRIMARY KEY REFERENCES margin_accounts(id),
    user_id BIGINT NOT NULL UNIQUE
);

CREATE INDEX IF NOT EXISTS idx_user_margin_accounts_user_id ON user_margin_accounts(user_id);

-- rollback DROP INDEX IF EXISTS idx_user_margin_accounts_user_id;
-- rollback DROP TABLE IF EXISTS user_margin_accounts;


-- changeset jovan:12
-- PR_03 C3.2: company_margin_accounts (FK ka margin_accounts.id; company_id unique).

CREATE TABLE IF NOT EXISTS company_margin_accounts (
    id         BIGINT NOT NULL PRIMARY KEY REFERENCES margin_accounts(id),
    company_id BIGINT NOT NULL UNIQUE
);

CREATE INDEX IF NOT EXISTS idx_company_margin_accounts_company_id ON company_margin_accounts(company_id);

-- rollback DROP INDEX IF EXISTS idx_company_margin_accounts_company_id;
-- rollback DROP TABLE IF EXISTS company_margin_accounts;
