--liquibase formatted sql

-- changeset jovan:11
-- PR_11 C11.16: dodaje daily_limit_remaining kolonu koju AtomicBalanceUpdater (PR_06 C6.3) referencira.
-- Pre PR_11 kolona nije postojala — atomic debitDailyLimit metoda bi pukla na "column does not exist".

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS daily_limit_remaining NUMERIC(19,2);

-- Backfill: koristi daily_limit kao initialnu vrednost za sve postojece account-e.
-- Ako daily_limit ne postoji u schema-i, postavi na NULL (servis tretira NULL kao "unlimited").
UPDATE accounts
   SET daily_limit_remaining = COALESCE(daily_limit, 0)
 WHERE daily_limit_remaining IS NULL;

-- rollback ALTER TABLE accounts DROP COLUMN IF EXISTS daily_limit_remaining;


-- changeset jovan:12
-- PR_11 C11.15: external_transfers tabela koju ExternalTransferRetryScheduler koristi.
-- Tabela je vec postojala u transfer-service do PR_02 C2.11 migracije; ovde se osigurava da
-- ima retry_count kolonu (mozda nedostaje kod starijih deploy-a).

ALTER TABLE external_transfers ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE external_transfers ADD COLUMN IF NOT EXISTS recipient_account VARCHAR(64);
ALTER TABLE external_transfers ADD COLUMN IF NOT EXISTS currency VARCHAR(3);

CREATE INDEX IF NOT EXISTS idx_external_transfers_status_retry ON external_transfers(status, retry_count);
CREATE INDEX IF NOT EXISTS idx_external_transfers_created_at ON external_transfers(created_at);

-- rollback DROP INDEX IF EXISTS idx_external_transfers_created_at;
-- rollback DROP INDEX IF EXISTS idx_external_transfers_status_retry;
-- rollback ALTER TABLE external_transfers DROP COLUMN IF EXISTS currency;
-- rollback ALTER TABLE external_transfers DROP COLUMN IF EXISTS recipient_account;
-- rollback ALTER TABLE external_transfers DROP COLUMN IF EXISTS retry_count;
