-- liquibase formatted sql

-- changeset codex:6
ALTER TABLE cards ADD COLUMN client_id BIGINT NOT NULL DEFAULT 0;

-- changeset codex:7
CREATE INDEX idx_cards_client_id ON cards (client_id);
