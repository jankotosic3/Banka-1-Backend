--liquibase formatted sql

-- changeset jovan:14-007
-- PR_14 C14.6: log za interne transfere koje banking-core posreduje za SAGA-e.
--
-- internalTransfer i reverseTransfer endpoint-i sadrze sebi specifican audit log,
-- da se SAGA kompenzacija (reverseTransfer) moze sigurno izvrsiti idempotentno —
-- ako orchestrator pozove reverseTransfer dva puta, drugi poziv vidi vec
-- 'REVERSED' status i vraca 200 bez dodatnog efekta.

CREATE TABLE IF NOT EXISTS internal_transfer_log (
    id BIGSERIAL PRIMARY KEY,
    transfer_id UUID NOT NULL UNIQUE,
    correlation_id VARCHAR(128) NOT NULL,
    from_account VARCHAR(32) NOT NULL,
    to_account VARCHAR(32) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(8) NOT NULL DEFAULT 'RSD',
    status VARCHAR(16) NOT NULL,  -- COMPLETED | REVERSED
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    reversed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_internal_transfer_correlation
    ON internal_transfer_log(correlation_id);

-- rollback DROP TABLE IF EXISTS internal_transfer_log;
