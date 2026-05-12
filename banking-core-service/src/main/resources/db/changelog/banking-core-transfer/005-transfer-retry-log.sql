--liquibase formatted sql

-- changeset jovan:transfer-retry-1
-- PR_13 C13.1: idempotency log za ExternalTransferRetryListener.

CREATE TABLE IF NOT EXISTS transfer_retry_log (
    transfer_id    BIGINT      NOT NULL,
    retry_attempt  INTEGER     NOT NULL,
    processed_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (transfer_id, retry_attempt)
);

CREATE INDEX IF NOT EXISTS idx_transfer_retry_log_processed_at ON transfer_retry_log(processed_at);

-- 30-day retention preko cron-a (TBD u nastavku).

-- rollback DROP TABLE IF EXISTS transfer_retry_log;
