-- =========================
-- PAYMENT TABLE
-- =========================
CREATE TABLE payment_table (
                               id BIGSERIAL PRIMARY KEY,
                               version BIGINT NOT NULL DEFAULT 0,
                               created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                               order_number VARCHAR(255) UNIQUE,

                               from_account_number VARCHAR(255) NOT NULL,
                               to_account_number VARCHAR(255) NOT NULL,

                               initial_amount NUMERIC(19, 4) NOT NULL,
                               final_amount NUMERIC(19, 4) NOT NULL,
                               commission NUMERIC(19, 4) NOT NULL,

                               sender_client_id BIGINT NOT NULL,
                               recipient_client_id BIGINT NOT NULL,
                               recipient_name VARCHAR(255) NOT NULL,

                               payment_code VARCHAR(3) NOT NULL,
                               reference_number VARCHAR(255),
                               payment_purpose VARCHAR(255) NOT NULL,

                               status VARCHAR(50) NOT NULL DEFAULT 'IN_PROGRESS',

                               from_currency VARCHAR(10) NOT NULL,
                               to_currency VARCHAR(10) NOT NULL,

                               exchange_rate NUMERIC(19, 8),

                               CONSTRAINT chk_payment_initial_amount_nonnegative
                                   CHECK (initial_amount >= 0),

                               CONSTRAINT chk_payment_final_amount_nonnegative
                                   CHECK (final_amount >= 0),

                               CONSTRAINT chk_payment_commission_nonnegative
                                   CHECK (commission >= 0),

                               CONSTRAINT chk_payment_exchange_rate_positive
                                   CHECK (exchange_rate IS NULL OR exchange_rate > 0),

                               CONSTRAINT chk_payment_code_three_digits
                                   CHECK (payment_code ~ '^[0-9]{3}$'),

    CONSTRAINT chk_payment_code_starts_with_2
        CHECK (payment_code ~ '^2[0-9]{2}$'),

    CONSTRAINT chk_payment_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'DENIED')),

    CONSTRAINT chk_payment_from_currency
        CHECK (from_currency IN ('RSD', 'EUR', 'CHF', 'USD', 'GBP', 'JPY', 'CAD', 'AUD')),

    CONSTRAINT chk_payment_to_currency
        CHECK (to_currency IN ('RSD', 'EUR', 'CHF', 'USD', 'GBP', 'JPY', 'CAD', 'AUD'))
);

-- =========================
-- INDEXES
-- =========================
CREATE INDEX idx_payment_order_number ON payment_table(order_number);
CREATE INDEX idx_payment_from_account_number ON payment_table(from_account_number);
CREATE INDEX idx_payment_to_account_number ON payment_table(to_account_number);
CREATE INDEX idx_payment_recipient_client_id ON payment_table(recipient_client_id);
CREATE INDEX idx_payment_status ON payment_table(status);
CREATE INDEX idx_payment_created_at ON payment_table(created_at);