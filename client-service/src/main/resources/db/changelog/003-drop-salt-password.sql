-- liquibase formatted sql

-- changeset client-service:3
ALTER TABLE clients DROP COLUMN IF EXISTS salt_password;
