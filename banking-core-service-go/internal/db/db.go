package db

import (
	"context"
	"database/sql"
	"fmt"
	"strings"

	"banka1/banking-core-service-go/internal/config"

	_ "github.com/jackc/pgx/v5/stdlib"
)

func Open(ctx context.Context, cfg config.Config) (*sql.DB, error) {
	conn, err := sql.Open("pgx", cfg.DatabaseURL())
	if err != nil {
		return nil, err
	}
	conn.SetMaxOpenConns(20)
	conn.SetMaxIdleConns(10)
	if err := conn.PingContext(ctx); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}

func Migrate(ctx context.Context, conn *sql.DB, cfg config.Config) error {
	if err := execStatements(ctx, conn, schemaSQL); err != nil {
		return err
	}
	if err := seed(ctx, conn, cfg); err != nil {
		return err
	}
	return nil
}

func execStatements(ctx context.Context, conn *sql.DB, script string) error {
	for _, statement := range strings.Split(script, ";") {
		statement = strings.TrimSpace(statement)
		if statement == "" {
			continue
		}
		if _, err := conn.ExecContext(ctx, statement); err != nil {
			return fmt.Errorf("execute migration statement %q: %w", previewSQL(statement), err)
		}
	}
	return nil
}

func seed(ctx context.Context, conn *sql.DB, cfg config.Config) error {
	statements := []struct {
		sql  string
		args []any
	}{
		{sql: seedCurrenciesSQL},
		{sql: seedActivitiesSQL},
		{sql: seedBankAccountSQL, args: []any{cfg.BankAccountNumber, cfg.BankClientID}},
		{sql: seedExchangeAccountSQL, args: []any{cfg.ExchangeAccountNumber, cfg.ExchangeClientID}},
		{sql: seedBankCurrencyAccountsSQL},
		{sql: seedStateCurrencyAccountsSQL},
	}
	for _, statement := range statements {
		if _, err := conn.ExecContext(ctx, statement.sql, statement.args...); err != nil {
			return fmt.Errorf("execute seed statement %q: %w", previewSQL(statement.sql), err)
		}
	}
	if cfg.SeedDevData {
		if err := execStatements(ctx, conn, devSeedClientAccountsSQL); err != nil {
			return fmt.Errorf("execute dev seed: %w", err)
		}
	}
	return nil
}

func previewSQL(statement string) string {
	compact := strings.Join(strings.Fields(statement), " ")
	if len(compact) > 100 {
		return compact[:100] + "..."
	}
	return compact
}

const schemaSQL = `
CREATE TABLE IF NOT EXISTS sifra_delatnosti_table (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT,
    sifra VARCHAR(50) NOT NULL UNIQUE,
    grana VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS sifra_delatnosti_sektori (
    sifra_delatnosti_id BIGINT NOT NULL REFERENCES sifra_delatnosti_table(id),
    sektor VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS currency_table (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT,
    naziv VARCHAR(255) NOT NULL,
    oznaka VARCHAR(20) NOT NULL UNIQUE,
    simbol VARCHAR(5) NOT NULL UNIQUE,
    opis VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL
);

CREATE TABLE IF NOT EXISTS currency_countries (
    currency_id BIGINT NOT NULL REFERENCES currency_table(id),
    country VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS company_table (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT,
    naziv VARCHAR(255) NOT NULL,
    maticni_broj VARCHAR(50) NOT NULL UNIQUE,
    poreski_broj VARCHAR(50) NOT NULL UNIQUE,
    sifra_delatnosti_id BIGINT NOT NULL REFERENCES sifra_delatnosti_table(id),
    adresa VARCHAR(255),
    vlasnik BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS account_table (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT DEFAULT 0,
    account_type VARCHAR(20) NOT NULL DEFAULT 'CHECKING',
    broj_racuna VARCHAR(50) NOT NULL UNIQUE,
    ime_vlasnika_racuna VARCHAR(255) NOT NULL DEFAULT 'SYSTEM',
    prezime_vlasnika_racuna VARCHAR(255) NOT NULL DEFAULT 'ACCOUNT',
    email VARCHAR(50),
    username VARCHAR(50),
    naziv_racuna VARCHAR(255) NOT NULL DEFAULT 'System account',
    vlasnik BIGINT NOT NULL,
    zaposlen BIGINT NOT NULL DEFAULT -1,
    stanje NUMERIC(19,2) NOT NULL DEFAULT 0,
    raspolozivo_stanje NUMERIC(19,2) NOT NULL DEFAULT 0,
    datum_i_vreme_kreiranja TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    datum_isteka DATE,
    currency_id BIGINT NOT NULL REFERENCES currency_table(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    dnevni_limit NUMERIC(19,2),
    mesecni_limit NUMERIC(19,2),
    dnevna_potrosnja NUMERIC(19,2) NOT NULL DEFAULT 0,
    mesecna_potrosnja NUMERIC(19,2) NOT NULL DEFAULT 0,
    company_id BIGINT,
    account_concrete VARCHAR(50),
    odrzavanje_racuna NUMERIC(19,2),
    account_ownership_type VARCHAR(20),
    daily_limit_remaining NUMERIC(19,2),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_due_to_client_id BIGINT
);

ALTER TABLE account_table ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE account_table ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE account_table ADD COLUMN IF NOT EXISTS deleted_due_to_client_id BIGINT;
ALTER TABLE account_table ALTER COLUMN datum_i_vreme_kreiranja SET DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_account_vlasnik ON account_table(vlasnik);
CREATE INDEX IF NOT EXISTS idx_account_broj ON account_table(broj_racuna);
CREATE INDEX IF NOT EXISTS idx_account_table_deleted_due_to_client_id
    ON account_table(deleted_due_to_client_id);

CREATE TABLE IF NOT EXISTS authorized_persons (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT DEFAULT 0,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    date_of_birth DATE NOT NULL,
    gender VARCHAR(20) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(50) NOT NULL,
    address VARCHAR(255) NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_authorized_person_identity
    ON authorized_persons (LOWER(email), LOWER(first_name), LOWER(last_name), date_of_birth);

CREATE TABLE IF NOT EXISTS authorized_person_card_ids (
    authorized_person_id BIGINT NOT NULL REFERENCES authorized_persons(id) ON DELETE CASCADE,
    card_id BIGINT NOT NULL,
    PRIMARY KEY (authorized_person_id, card_id)
);

CREATE TABLE IF NOT EXISTS cards (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT DEFAULT 0,
    card_number VARCHAR(16) NOT NULL UNIQUE,
    card_type VARCHAR(20) NOT NULL DEFAULT 'DEBIT',
    card_name VARCHAR(50) NOT NULL,
    creation_date DATE NOT NULL,
    expiration_date DATE NOT NULL,
    account_number VARCHAR(50) NOT NULL,
    client_id BIGINT NOT NULL,
    authorized_person_id BIGINT REFERENCES authorized_persons(id),
    cvv VARCHAR(255) NOT NULL,
    card_limit NUMERIC(19,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_due_to_client_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE cards ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE cards ADD COLUMN IF NOT EXISTS deleted_due_to_client_id BIGINT;
ALTER TABLE cards ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE cards ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_cards_account_number ON cards(account_number);
CREATE INDEX IF NOT EXISTS idx_cards_client_id ON cards(client_id);
CREATE INDEX IF NOT EXISTS idx_cards_authorized_person_id ON cards(authorized_person_id);
CREATE INDEX IF NOT EXISTS idx_cards_status ON cards(status);
CREATE INDEX IF NOT EXISTS idx_cards_deleted_due_to_client_id ON cards(deleted_due_to_client_id);

CREATE TABLE IF NOT EXISTS verification_sessions (
    id BIGSERIAL PRIMARY KEY,
    client_id BIGINT NOT NULL,
    code VARCHAR(255) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    related_entity_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    status VARCHAR(50) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_verification_sessions_client_id
    ON verification_sessions(client_id);
CREATE INDEX IF NOT EXISTS idx_verification_sessions_status_expires_at
    ON verification_sessions(status, expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_verification_sessions_pending_unique
    ON verification_sessions(client_id, operation_type, related_entity_id)
    WHERE status = 'PENDING';
CREATE UNIQUE INDEX IF NOT EXISTS uk_verification_sessions_pending
    ON verification_sessions(operation_type, related_entity_id)
    WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS saga_idempotency_log (
    event_id VARCHAR(64) NOT NULL,
    listener VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, listener)
);

CREATE INDEX IF NOT EXISTS idx_saga_idem_processed_at
    ON saga_idempotency_log(processed_at);

CREATE TABLE IF NOT EXISTS margin_accounts (
    id BIGSERIAL PRIMARY KEY,
    initial_margin NUMERIC(19,2) NOT NULL CHECK (initial_margin >= 0),
    loan_value NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (loan_value >= 0),
    maintenance_margin NUMERIC(19,2) NOT NULL CHECK (maintenance_margin >= 0),
    bank_participation NUMERIC(5,4) NOT NULL CHECK (bank_participation >= 0 AND bank_participation <= 1),
    account_number VARCHAR(16) NOT NULL UNIQUE CHECK (account_number ~ '^[0-9]{16}$'),
    currency VARCHAR(3) NOT NULL DEFAULT 'RSD',
    active BOOLEAN NOT NULL DEFAULT true,
    deleted BOOLEAN NOT NULL DEFAULT false,
    owner_kind VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_margin_accounts_owner_kind
    ON margin_accounts(owner_kind);
CREATE INDEX IF NOT EXISTS idx_margin_accounts_active
    ON margin_accounts(active)
    WHERE deleted = false;

CREATE TABLE IF NOT EXISTS user_margin_accounts (
    id BIGINT NOT NULL PRIMARY KEY REFERENCES margin_accounts(id),
    user_id BIGINT NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS company_margin_accounts (
    id BIGINT NOT NULL PRIMARY KEY REFERENCES margin_accounts(id),
    company_id BIGINT NOT NULL UNIQUE
);

CREATE INDEX IF NOT EXISTS idx_user_margin_accounts_user_id ON user_margin_accounts(user_id);
CREATE INDEX IF NOT EXISTS idx_company_margin_accounts_company_id ON company_margin_accounts(company_id);

CREATE TABLE IF NOT EXISTS margin_transactions (
    id BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(16) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    loan_value_after NUMERIC(19,2),
    initial_margin_after NUMERIC(19,2),
    description VARCHAR(255),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_margin_tx_account_number ON margin_transactions(account_number);
CREATE INDEX IF NOT EXISTS idx_margin_tx_occurred_at ON margin_transactions(occurred_at);

CREATE TABLE IF NOT EXISTS fund_reservations (
    id BIGSERIAL PRIMARY KEY,
    reservation_id UUID NOT NULL UNIQUE,
    correlation_id VARCHAR(128) NOT NULL,
    owner_id BIGINT NOT NULL,
    account_number VARCHAR(32) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(8) NOT NULL DEFAULT 'RSD',
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    released_at TIMESTAMP,
    committed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fund_reservations_correlation
    ON fund_reservations(correlation_id);
CREATE INDEX IF NOT EXISTS idx_fund_reservations_owner_status
    ON fund_reservations(owner_id, status);

CREATE TABLE IF NOT EXISTS internal_transfer_log (
    id BIGSERIAL PRIMARY KEY,
    transfer_id UUID NOT NULL UNIQUE,
    correlation_id VARCHAR(128) NOT NULL,
    from_account VARCHAR(32) NOT NULL,
    to_account VARCHAR(32) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(8) NOT NULL DEFAULT 'RSD',
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    reversed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_internal_transfer_correlation
    ON internal_transfer_log(correlation_id);

CREATE TABLE IF NOT EXISTS interbank_reservations (
    id BIGSERIAL PRIMARY KEY,
    reservation_id UUID NOT NULL UNIQUE,
    transaction_id_routing INT NOT NULL,
    transaction_id_local VARCHAR(64) NOT NULL,
    account_number VARCHAR(32) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    amount NUMERIC(20,4) NOT NULL CHECK (amount > 0),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    finalized_at TIMESTAMP WITH TIME ZONE
);
-- Self-heal postojece baze: CREATE TABLE IF NOT EXISTS ne menja kolonu, a racuni su 19 cifara (bili 18).
-- VAZNO: komentari u schemaSQL NE smeju sadrzati tacka-zarez jer execStatements deli skriptu po njoj.
ALTER TABLE interbank_reservations ALTER COLUMN account_number TYPE VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_interbank_reservations_tx
    ON interbank_reservations(transaction_id_routing, transaction_id_local);
CREATE INDEX IF NOT EXISTS idx_interbank_reservations_account_status
    ON interbank_reservations(account_number, status);

CREATE TABLE IF NOT EXISTS gdpr_event_log (
    event_id VARCHAR(64) NOT NULL,
    listener VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    summary VARCHAR(255),
    PRIMARY KEY (event_id, listener)
);

CREATE INDEX IF NOT EXISTS idx_gdpr_event_log_processed_at
    ON gdpr_event_log(processed_at);

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS transfer_retry_log (
    transfer_id BIGINT NOT NULL,
    retry_attempt INTEGER NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (transfer_id, retry_attempt)
);

CREATE INDEX IF NOT EXISTS idx_transfer_retry_log_processed_at
    ON transfer_retry_log(processed_at);

CREATE TABLE IF NOT EXISTS external_transfers (
    id BIGSERIAL PRIMARY KEY,
    from_account VARCHAR(64) NOT NULL,
    recipient_account VARCHAR(64) NOT NULL,
    amount NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    clearing_house_ref VARCHAR(64),
    failure_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_external_transfers_status_retry
    ON external_transfers(status, retry_count);
CREATE INDEX IF NOT EXISTS idx_external_transfers_created_at
    ON external_transfers(created_at);

CREATE TABLE IF NOT EXISTS payment_table (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    order_number VARCHAR(255) UNIQUE,
    from_account_number VARCHAR(255) NOT NULL,
    to_account_number VARCHAR(255) NOT NULL,
    initial_amount NUMERIC(19,4) NOT NULL,
    final_amount NUMERIC(19,4) NOT NULL,
    commission NUMERIC(19,4) NOT NULL,
    sender_client_id BIGINT NOT NULL,
    recipient_client_id BIGINT NOT NULL,
    recipient_name VARCHAR(255) NOT NULL,
    payment_code VARCHAR(3) NOT NULL,
    reference_number VARCHAR(255),
    payment_purpose VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'IN_PROGRESS',
    from_currency VARCHAR(10) NOT NULL,
    to_currency VARCHAR(10) NOT NULL,
    exchange_rate NUMERIC(19,8)
);

CREATE INDEX IF NOT EXISTS idx_payment_order_number ON payment_table(order_number);
CREATE INDEX IF NOT EXISTS idx_payment_from_account_number ON payment_table(from_account_number);
CREATE INDEX IF NOT EXISTS idx_payment_to_account_number ON payment_table(to_account_number);
CREATE INDEX IF NOT EXISTS idx_payment_recipient_client_id ON payment_table(recipient_client_id);
CREATE INDEX IF NOT EXISTS idx_payment_sender_client_id ON payment_table(sender_client_id);
CREATE INDEX IF NOT EXISTS idx_payment_status ON payment_table(status);
CREATE INDEX IF NOT EXISTS idx_payment_created_at ON payment_table(created_at);

CREATE TABLE IF NOT EXISTS payment_recipient (
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

CREATE INDEX IF NOT EXISTS idx_payment_recipient_owner ON payment_recipient(owner_client_id);

CREATE TABLE IF NOT EXISTS transfers (
    id BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(255) NOT NULL UNIQUE,
    client_id BIGINT NOT NULL,
    from_account_number VARCHAR(255) NOT NULL,
    to_account_number VARCHAR(255) NOT NULL,
    initial_amount NUMERIC(19,4) NOT NULL,
    final_amount NUMERIC(19,4) NOT NULL,
    exchange_rate NUMERIC(19,6),
    commission NUMERIC(19,4) NOT NULL,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verification_session_id VARCHAR(255) NOT NULL UNIQUE,
    version BIGINT DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_transfers_client_id ON transfers(client_id);
CREATE INDEX IF NOT EXISTS idx_transfers_order_number ON transfers(order_number);
CREATE INDEX IF NOT EXISTS idx_transfers_accounts ON transfers(from_account_number, to_account_number);

CREATE TABLE IF NOT EXISTS transaction_record_table (
    id BIGSERIAL PRIMARY KEY,
    version BIGINT DEFAULT 0,
    account_number VARCHAR(255) NOT NULL,
    bank_account_number VARCHAR(255) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_transaction_record_account_number
    ON transaction_record_table(account_number);
CREATE INDEX IF NOT EXISTS idx_transaction_record_created_at
    ON transaction_record_table(created_at);
`

const seedCurrenciesSQL = `
INSERT INTO currency_table (naziv, oznaka, simbol, opis, status)
VALUES
    ('Serbian dinar', 'RSD', 'RSD', 'Serbian dinar', 'ACTIVE'),
    ('Euro', 'EUR', 'EUR', 'Euro', 'ACTIVE'),
    ('Swiss franc', 'CHF', 'CHF', 'Swiss franc', 'ACTIVE'),
    ('US dollar', 'USD', 'USD', 'US dollar', 'ACTIVE'),
    ('British pound', 'GBP', 'GBP', 'British pound', 'ACTIVE'),
    ('Japanese yen', 'JPY', 'JPY', 'Japanese yen', 'ACTIVE'),
    ('Canadian dollar', 'CAD', 'CAD', 'Canadian dollar', 'ACTIVE'),
    ('Australian dollar', 'AUD', 'AUD', 'Australian dollar', 'ACTIVE')
ON CONFLICT (oznaka) DO NOTHING
`

const seedActivitiesSQL = `
INSERT INTO sifra_delatnosti_table (sifra, grana)
VALUES
    ('6201', 'Racunarsko programiranje'),
    ('6419', 'Ostalo monetarno posredovanje'),
    ('7022', 'Konsultantske aktivnosti')
ON CONFLICT (sifra) DO NOTHING
`

const seedBankAccountSQL = `
INSERT INTO account_table (
    broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna, naziv_racuna,
    vlasnik, zaposlen, stanje, raspolozivo_stanje, currency_id, status,
    dnevna_potrosnja, mesecna_potrosnja, account_type, account_ownership_type
)
SELECT $1, 'Banka', 'Banka', 'Banka RSD', $2, -1, 1000000000, 1000000000, c.id,
       'ACTIVE', 0, 0, 'CHECKING', 'BUSINESS'
  FROM currency_table c
 WHERE c.oznaka = 'RSD'
ON CONFLICT (broj_racuna) DO NOTHING
`

const seedExchangeAccountSQL = `
INSERT INTO account_table (
    broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna, naziv_racuna,
    vlasnik, zaposlen, stanje, raspolozivo_stanje, currency_id, status,
    dnevna_potrosnja, mesecna_potrosnja, account_type, account_ownership_type
)
SELECT $1, 'Exchange', 'Exchange', 'Exchange RSD', $2, -1, 1000000000, 1000000000, c.id,
       'ACTIVE', 0, 0, 'CHECKING', 'BUSINESS'
  FROM currency_table c
 WHERE c.oznaka = 'RSD'
ON CONFLICT (broj_racuna) DO NOTHING
`

const seedBankCurrencyAccountsSQL = `
INSERT INTO account_table (
    broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna, naziv_racuna,
    vlasnik, zaposlen, stanje, raspolozivo_stanje, currency_id, status,
    dnevna_potrosnja, mesecna_potrosnja, account_type, account_ownership_type
)
SELECT '1110001' || (100000000 + c.id)::text || '12',
       'Banka', 'Banka', 'Banka ' || c.oznaka,
       -1, -1, 1000000000, 1000000000, c.id,
       'ACTIVE', 0, 0, CASE WHEN c.oznaka = 'RSD' THEN 'CHECKING' ELSE 'FX' END, 'BUSINESS'
  FROM currency_table c
 WHERE NOT EXISTS (
       SELECT 1 FROM account_table a WHERE a.vlasnik = -1 AND a.currency_id = c.id
 )
ON CONFLICT (broj_racuna) DO NOTHING
`

const seedStateCurrencyAccountsSQL = `
INSERT INTO account_table (
    broj_racuna, ime_vlasnika_racuna, prezime_vlasnika_racuna, naziv_racuna,
    vlasnik, zaposlen, stanje, raspolozivo_stanje, currency_id, status,
    dnevna_potrosnja, mesecna_potrosnja, account_type, account_ownership_type
)
SELECT '1110001' || (200000000 + c.id)::text || '12',
       'Republika', 'Srbija', 'Drzavni racun ' || c.oznaka,
       -2, -1, 1000000000, 1000000000, c.id,
       'ACTIVE', 0, 0, CASE WHEN c.oznaka = 'RSD' THEN 'CHECKING' ELSE 'FX' END, 'BUSINESS'
  FROM currency_table c
 WHERE NOT EXISTS (
       SELECT 1 FROM account_table a WHERE a.vlasnik = -2 AND a.currency_id = c.id
 )
ON CONFLICT (broj_racuna) DO NOTHING
`
