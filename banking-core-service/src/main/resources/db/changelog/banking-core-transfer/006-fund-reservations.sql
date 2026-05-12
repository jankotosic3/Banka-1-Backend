--liquibase formatted sql

-- changeset jovan:14-006
-- PR_14 C14.6: tabela koja drzi rezervacije sredstava za SAGA korake.
--
-- SAGA orchestrator (npr. OtcExerciseSaga.run step 1) zove banking-core
-- POST /transactions/internal/reserve-funds. Banking-core odmah skida sumu sa
-- klijentskog tekuceg racuna preko account-service (REST debit) i upisuje red
-- ovde sa status='HELD'. Pri kompenzaciji (DELETE /reservations/{id}) banking-core
-- vraca novac kreditom na isti racun i prelazi na status='RELEASED'.
--
-- Pri commit-u (npr. step 3 OTC_EXERCISE: pravi transfer ka prodavcu) banking-core
-- ne koristi rezervaciju vise — samo prelazi status na 'COMMITTED' radi audit-a.
-- Uklanjanje reda nije potrebno; istorija ostaje za izvestaj.

CREATE TABLE IF NOT EXISTS fund_reservations (
    id BIGSERIAL PRIMARY KEY,
    reservation_id UUID NOT NULL UNIQUE,
    correlation_id VARCHAR(128) NOT NULL,
    owner_id BIGINT NOT NULL,
    account_number VARCHAR(32) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(8) NOT NULL DEFAULT 'RSD',
    status VARCHAR(16) NOT NULL,  -- HELD | RELEASED | COMMITTED
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    released_at TIMESTAMP,
    committed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fund_reservations_correlation
    ON fund_reservations(correlation_id);

CREATE INDEX IF NOT EXISTS idx_fund_reservations_owner_status
    ON fund_reservations(owner_id, status);

-- rollback DROP TABLE IF EXISTS fund_reservations;
