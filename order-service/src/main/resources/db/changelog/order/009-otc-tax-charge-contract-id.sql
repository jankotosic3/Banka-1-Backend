-- liquibase formatted sql

-- changeset order:9
ALTER TABLE tax_charges ADD COLUMN otc_contract_id BIGINT;
CREATE UNIQUE INDEX uk_tax_charges_otc_contract ON tax_charges (otc_contract_id) WHERE otc_contract_id IS NOT NULL;
