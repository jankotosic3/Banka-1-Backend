--liquibase formatted sql

-- changeset jovan:bank-seed-1 context:always
-- PR_13 C13.5: seed za bank + exchange interne racune koje BankToExchangeTransferService
-- referencira (PR_12 C12.2). Bez ovih redova, marzni buy/sell flow puca na "Account ne postoji".
--
-- Account brojevi su deterministicki — usaglaseni sa property defaults u
-- BankToExchangeTransferService:
--   banka.banking.bank-account-number=1234567812345670
--   banka.banking.exchange-account-number=9876543212345674
--
-- Production deploy moze override-ovati ova property-ja, ali tada mora i ovde
-- ALTER UPDATE-ovati account_number kolone, ili koristiti drugaciji seed.
--
-- Liquibase context:always znaci da seed ide u SVAKOJ DB-i (i prod i dev),
-- ne samo dev (za razliku od PR_01 C1.3 dev seed-a).

INSERT INTO accounts (
    id, account_number, balance, currency, status,
    owner_id, owner_kind, daily_limit, daily_limit_remaining,
    deleted, created_at, version
)
SELECT
    nextval(pg_get_serial_sequence('accounts', 'id')),
    '1234567812345670',
    1000000000.00,            -- bank ima 1 milijardu RSD startno
    'RSD',
    'ACTIVE',
    NULL,                     -- bank account nema owner-client-a
    'BANK',                   -- discriminator
    NULL,                     -- bez daily limit-a
    NULL,
    false,
    now(),
    0
WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '1234567812345670');

INSERT INTO accounts (
    id, account_number, balance, currency, status,
    owner_id, owner_kind, daily_limit, daily_limit_remaining,
    deleted, created_at, version
)
SELECT
    nextval(pg_get_serial_sequence('accounts', 'id')),
    '9876543212345674',
    500000000.00,             -- exchange ima 500M RSD startno (likvidnost)
    'RSD',
    'ACTIVE',
    NULL,
    'EXCHANGE',
    NULL,
    NULL,
    false,
    now(),
    0
WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_number = '9876543212345674');

-- rollback DELETE FROM accounts WHERE account_number IN ('1234567812345670', '9876543212345674');
