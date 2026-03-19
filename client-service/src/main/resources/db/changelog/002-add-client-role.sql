-- liquibase formatted sql

-- changeset client-service:3
ALTER TABLE clients ADD COLUMN role VARCHAR(50) NOT NULL DEFAULT 'CLIENT_BASIC';
