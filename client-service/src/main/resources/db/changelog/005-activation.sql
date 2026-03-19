-- liquibase formatted sql

-- changeset client-service:6
ALTER TABLE clients ADD COLUMN aktivan BOOLEAN NOT NULL DEFAULT FALSE;

-- Svi postojeci klijenti (seedovani sa lozinkama) treba da budu aktivni
UPDATE clients SET aktivan = TRUE WHERE deleted = false;

-- changeset client-service:7
CREATE TABLE client_confirmation_token
(
    id                   BIGSERIAL PRIMARY KEY,
    value                VARCHAR(255) NOT NULL UNIQUE,
    expiration_date_time TIMESTAMP,
    klijent_id           BIGINT       NOT NULL UNIQUE REFERENCES clients (id),
    version              BIGINT                DEFAULT 0,
    deleted              BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMP             DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP             DEFAULT CURRENT_TIMESTAMP
);
