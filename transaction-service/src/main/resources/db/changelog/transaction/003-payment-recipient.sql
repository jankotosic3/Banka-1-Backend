-- liquibase formatted sql

-- changeset banka1:003-payment-recipient-1
-- Celina 2: tabela primaoca placanja per klijent (CRUD).
CREATE TABLE payment_recipient (
    id BIGSERIAL PRIMARY KEY,
    owner_client_id BIGINT NOT NULL,
    naziv VARCHAR(100) NOT NULL,
    broj_racuna VARCHAR(50) NOT NULL,
    version BIGINT DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payment_recipient_owner_naziv UNIQUE (owner_client_id, naziv)
);

CREATE INDEX idx_payment_recipient_owner ON payment_recipient (owner_client_id);
