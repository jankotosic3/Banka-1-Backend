-- liquibase formatted sql

-- changeset client-service:5
ALTER TABLE clients DROP COLUMN IF EXISTS salt_password;
