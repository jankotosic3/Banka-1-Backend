-- liquibase formatted sql

-- changeset order:11
ALTER TABLE orders ADD COLUMN purchase_for VARCHAR(20);
ALTER TABLE orders ADD COLUMN fund_id BIGINT;