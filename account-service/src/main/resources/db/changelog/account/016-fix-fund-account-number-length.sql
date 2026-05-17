-- liquibase formatted sql

-- changeset account-service:016-fix-fund-account-number-length context:dev
-- comment: DEV-ONLY align fund account numbers with internal debit/credit validation.

UPDATE account_table
SET broj_racuna = '1110001999999911123'
WHERE broj_racuna = '1110001999999911';

UPDATE account_table
SET broj_racuna = '1110001999999921123'
WHERE broj_racuna = '1110001999999921';
