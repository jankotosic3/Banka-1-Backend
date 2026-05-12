--liquibase formatted sql

-- changeset jovan:gdpr-1
-- PR_11 C11.13: gdpr_event_log za idempotency check u GdprClientDeletedListener-u.

CREATE TABLE IF NOT EXISTS gdpr_event_log (
    event_id     VARCHAR(64)  NOT NULL,
    listener     VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    summary      VARCHAR(255),
    PRIMARY KEY (event_id, listener)
);

CREATE INDEX IF NOT EXISTS idx_gdpr_event_log_processed_at ON gdpr_event_log(processed_at);

-- 30-day retention preko cron task-a (TBD).

-- rollback DROP TABLE IF EXISTS gdpr_event_log;
