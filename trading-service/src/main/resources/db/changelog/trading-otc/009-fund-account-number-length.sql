--liquibase formatted sql

-- changeset trading-service:009-fund-account-number-length context:dev
-- comment: DEV-ONLY fund account numbers must satisfy account debit/credit validation.

ALTER TABLE investment_funds
    DROP CONSTRAINT IF EXISTS investment_funds_account_number_check;

ALTER TABLE investment_funds
    ALTER COLUMN account_number TYPE VARCHAR(50);

UPDATE investment_funds
SET account_number = '1110001999999911123'
WHERE naziv = 'Konzervativni RSD';

UPDATE investment_funds
SET account_number = '1110001999999921123'
WHERE naziv = 'Agresivni Tech USD';
