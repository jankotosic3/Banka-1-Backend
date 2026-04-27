-- liquibase formatted sql

-- changeset client-service:1
CREATE TABLE clients (
    id BIGSERIAL PRIMARY KEY,
    ime VARCHAR(255) NOT NULL,
    prezime VARCHAR(255) NOT NULL,
    datum_rodjenja BIGINT NOT NULL,
    pol VARCHAR(10) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    broj_telefona VARCHAR(255),
    adresa VARCHAR(255),
    password VARCHAR(255),
    salt_password VARCHAR(255),
    jmbg VARCHAR(13) NOT NULL UNIQUE,

    -- BaseEntity fields
    version BIGINT DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- changeset client-service:2
CREATE TABLE client_permissions (
                                      client_id BIGINT NOT NULL,
                                      permission VARCHAR(100) NOT NULL,

                                      PRIMARY KEY (client_id, permission),

                                      CONSTRAINT fk_zp_zaposlen
                                          FOREIGN KEY (client_id)
                                              REFERENCES employees(id)
                                              ON DELETE CASCADE
);


-- changeset client-service:3
-- Index za brzo pretrazivanje po imenu i prezimenu
CREATE INDEX idx_clients_ime_prezime ON clients (ime, prezime);

-- Index za brzo pretrazivanje po email-u
CREATE INDEX idx_clients_email ON clients (email);

-- Index za JMBG lookup
CREATE UNIQUE INDEX idx_clients_jmbg ON clients (jmbg) WHERE deleted = false;

-- Partial Index za aktivne klijente
CREATE INDEX idx_clients_active ON clients (deleted, id) WHERE deleted = false;
