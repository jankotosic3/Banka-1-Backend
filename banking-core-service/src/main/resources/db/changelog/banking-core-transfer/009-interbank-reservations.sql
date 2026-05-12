--liquibase formatted sql

-- changeset interbank:32-bcore-009-interbank-reservations
-- PR_32 Phase 11: rezervacije sredstava koje upisuju strani banking nodovi
-- (interbank-service preko BankingCoreInternalClient) tokom 2PC interbank
-- transfera. Ove rezervacije razlikuju se od fund_reservations (006) jer:
--
--   * fund_reservations vec drzi unutar-banka SAGA korake (OTC_EXERCISE step 1,
--     FUND_SUBSCRIBE step 1) — kljuc je correlation_id + owner_id.
--   * interbank_reservations drzi inter-banka 2PC korake — kljuc je par
--     (transaction_id_routing, transaction_id_local) koji dolazi iz interbank
--     transakcionog protokola (Tim 2 §4.6).
--
-- Pattern: reserveMonas() rezervise iznos (smanji raspolozivoStanje), commit
-- skida i sa stanje (full balance), release vraca raspolozivoStanje. Idempotentno
-- prema reservation_id.

CREATE TABLE interbank_reservations (
    id BIGSERIAL PRIMARY KEY,
    reservation_id UUID NOT NULL UNIQUE,
    transaction_id_routing INT NOT NULL,
    transaction_id_local VARCHAR(64) NOT NULL,
    account_number VARCHAR(18) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    amount NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    status VARCHAR(16) NOT NULL,                     -- HELD / COMMITTED / RELEASED
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    finalized_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_interbank_reservations_tx
    ON interbank_reservations(transaction_id_routing, transaction_id_local);

CREATE INDEX idx_interbank_reservations_account_status
    ON interbank_reservations(account_number, status);

-- rollback DROP TABLE IF EXISTS interbank_reservations;
