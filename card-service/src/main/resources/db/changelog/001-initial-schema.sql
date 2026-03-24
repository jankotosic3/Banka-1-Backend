-- liquibase formatted sql

-- changeset codex:1
CREATE TABLE cards (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    card_number VARCHAR(16) NOT NULL UNIQUE,
    card_type VARCHAR(20) NOT NULL,
    card_name VARCHAR(50) NOT NULL,
    creation_date DATE NOT NULL,
    expiration_date DATE NOT NULL,
    account_number VARCHAR(50) NOT NULL,
    cvv VARCHAR(255) NOT NULL,
    card_limit DECIMAL(19, 2) NOT NULL,
    status VARCHAR(20) NOT NULL
);

-- changeset codex:2
CREATE INDEX idx_cards_account_number ON cards (account_number);

-- changeset codex:3
CREATE INDEX idx_cards_status ON cards (status);
