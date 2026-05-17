-- liquibase formatted sql

-- changeset account-service:015-seed-fund-accounts context:dev
-- comment: DEV-ONLY system accounts for investment funds seeded by trading-service.

INSERT INTO account_table (
    version, account_type, broj_racuna,
    ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna,
    vlasnik, zaposlen,
    stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka,
    currency_id, status,
    dnevni_limit, mesecni_limit,
    dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT
    0, 'CHECKING', '1110001999999911',
    'SYSTEM', 'Investicioni fond: Konzervativni RSD',
    'system+-1001@banka1.local', 'system--1001', 'Investicioni fond: Konzervativni RSD',
    -1001, -1,
    5000.00, 5000.00,
    NOW(), '2076-01-01',
    c.id, 'ACTIVE',
    999999999.99, 999999999.99,
    0.00, 0.00,
    NULL, 'STANDARDNI', 0.00, NULL
FROM currency_table c
WHERE c.oznaka = 'RSD'
  AND NOT EXISTS (SELECT 1 FROM account_table WHERE broj_racuna = '1110001999999911');

INSERT INTO account_table (
    version, account_type, broj_racuna,
    ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna,
    vlasnik, zaposlen,
    stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka,
    currency_id, status,
    dnevni_limit, mesecni_limit,
    dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT
    0, 'CHECKING', '1110001999999921',
    'SYSTEM', 'Investicioni fond: Agresivni Tech USD',
    'system+-1002@banka1.local', 'system--1002', 'Investicioni fond: Agresivni Tech USD',
    -1002, -1,
    180000.00, 180000.00,
    NOW(), '2076-01-01',
    c.id, 'ACTIVE',
    999999999.99, 999999999.99,
    0.00, 0.00,
    NULL, 'STANDARDNI', 0.00, NULL
FROM currency_table c
WHERE c.oznaka = 'RSD'
  AND NOT EXISTS (SELECT 1 FROM account_table WHERE broj_racuna = '1110001999999921');

-- Marko needs an EUR account for the foreign-currency fund redemption test.
INSERT INTO account_table (
    version, account_type, broj_racuna,
    ime_vlasnika_racuna, prezime_vlasnika_racuna,
    email, username, naziv_racuna,
    vlasnik, zaposlen,
    stanje, raspolozivo_stanje,
    datum_i_vreme_kreiranja, datum_isteka,
    currency_id, status,
    dnevni_limit, mesecni_limit,
    dnevna_potrosnja, mesecna_potrosnja,
    company_id, account_concrete, odrzavanje_racuna, account_ownership_type
)
SELECT
    0, 'FX', '1110001100000000221',
    'Marko', 'Markovic',
    NULL, NULL, 'Devizni racun EUR',
    1, 1,
    0.00, 0.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    5000.00, 20000.00,
    0.00, 0.00,
    NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c
WHERE c.oznaka = 'EUR'
  AND NOT EXISTS (SELECT 1 FROM account_table WHERE broj_racuna = '1110001100000000221');
