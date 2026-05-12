-- liquibase formatted sql

-- changeset card-service:1
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

-- changeset card-service:2
CREATE INDEX idx_cards_account_number ON cards (account_number);

-- changeset card-service:3
CREATE INDEX idx_cards_status ON cards (status);

-- changeset card-service:6
ALTER TABLE cards ADD COLUMN client_id BIGINT NOT NULL DEFAULT 0;

-- changeset card-service:7
CREATE INDEX idx_cards_client_id ON cards (client_id);

-- changeset card-service:8
ALTER TABLE cards ADD COLUMN authorized_person_id BIGINT;

-- changeset card-service:9
CREATE INDEX idx_cards_authorized_person_id ON cards (authorized_person_id);

-- changeset card-service:10
CREATE TABLE authorized_persons (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    date_of_birth DATE NOT NULL,
    gender VARCHAR(20) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(50) NOT NULL,
    address VARCHAR(255) NOT NULL
);

-- changeset card-service:11
CREATE TABLE authorized_person_card_ids (
    authorized_person_id BIGINT NOT NULL,
    card_id BIGINT NOT NULL,
    PRIMARY KEY (authorized_person_id, card_id),
    CONSTRAINT fk_authorized_person_card_ids_person
        FOREIGN KEY (authorized_person_id) REFERENCES authorized_persons (id)
);

-- changeset card-service:12
CREATE TABLE card_request_verifications (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    account_number VARCHAR(50) NOT NULL,
    client_id BIGINT NOT NULL,
    ownership_type VARCHAR(20) NOT NULL,
    recipient_type VARCHAR(30) NOT NULL,
    card_brand VARCHAR(20) NOT NULL,
    card_limit DECIMAL(19, 2) NOT NULL,
    verification_code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    authorized_person_id BIGINT,
    authorized_first_name VARCHAR(100),
    authorized_last_name VARCHAR(100),
    authorized_date_of_birth DATE,
    authorized_gender VARCHAR(20),
    authorized_email VARCHAR(255),
    authorized_phone VARCHAR(50),
    authorized_address VARCHAR(255)
);

-- changeset card-service:13
CREATE INDEX idx_card_req_ver_client_id ON card_request_verifications (client_id);

-- changeset card-service:14
CREATE INDEX idx_card_req_ver_account_number ON card_request_verifications (account_number);

-- changeset card-service:15
CREATE INDEX idx_card_req_ver_expires_at ON card_request_verifications (expires_at);
