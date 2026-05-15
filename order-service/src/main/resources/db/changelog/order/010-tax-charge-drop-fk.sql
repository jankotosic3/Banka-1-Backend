-- liquibase formatted sql

-- changeset order:10
-- Drop FK constraints on tax_charges so that portfolio-average fallback rows (buy_transaction_id=-1)
-- and OTC contract IDs (which are not transaction IDs) can be stored without violation.
ALTER TABLE tax_charges DROP CONSTRAINT IF EXISTS tax_charges_sell_transaction_id_fkey;
ALTER TABLE tax_charges DROP CONSTRAINT IF EXISTS tax_charges_buy_transaction_id_fkey;
