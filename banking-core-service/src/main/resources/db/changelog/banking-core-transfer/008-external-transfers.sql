--liquibase formatted sql

-- changeset jovan:15-008
-- PR_15 C15.1: kreira external_transfers tabelu u banking_core DB-u.
--
-- ExternalTransferRetryScheduler (BV-7 cron) i ExternalTransferRetryListener
-- (RabbitMQ konzumeri) u banking-core-u rade SQL nad ovom tabelom. Pre PR_15
-- tabela nije postojala u banking_core DB — bila je samo ALTER-ovana u
-- account-service migration-u, sto je domenski netacno (transferi nisu deo
-- account schema-e).

CREATE TABLE IF NOT EXISTS external_transfers (
    id                 BIGSERIAL    PRIMARY KEY,
    from_account       VARCHAR(64)  NOT NULL,
    recipient_account  VARCHAR(64)  NOT NULL,
    amount             NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency           VARCHAR(3)   NOT NULL,
    status             VARCHAR(16)  NOT NULL,  -- PENDING | RETRYING | COMPLETED | ESCALATED | FAILED
    retry_count        INTEGER      NOT NULL DEFAULT 0,
    clearing_house_ref VARCHAR(64),
    failure_reason     VARCHAR(255),
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_external_transfers_status_retry
    ON external_transfers(status, retry_count);

CREATE INDEX IF NOT EXISTS idx_external_transfers_created_at
    ON external_transfers(created_at);

-- rollback DROP TABLE IF EXISTS external_transfers;
