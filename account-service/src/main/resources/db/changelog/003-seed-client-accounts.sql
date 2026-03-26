-- liquibase formatted sql

-- changeset account-service:3
-- comment: Seed checking and FX accounts for the 8 test clients (IDs 1-8 from client-service)
-- Account number format: 111(bank) + 0001(branch) + 10 random digits + 2-digit type code = 19 digits
-- Type codes: STANDARDNI=11, STEDNI=13, ZA_MLADE=15, FX_PERSONAL=21, FX_BUSINESS=22

-- =========================
-- MARKO MARKOVIC (client_id = 1)
-- STANDARDNI checking account, RSD
-- =========================
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
    0, 'CHECKING', '1110001100000000111',
    'Marko', 'Markovic',
    'marko.markovic@banka.com', 'marko.markovic', 'Tekuci racun',
    1, 1,
    100000.00, 100000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    250000.00, 1000000.00,
    0.00, 0.00,
    NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD';

-- =========================
-- ANA ANIC (client_id = 2)
-- STANDARDNI checking account, RSD
-- =========================
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
    0, 'CHECKING', '1110001200000000111',
    'Ana', 'Anic',
    'ana.anic@banka.com', 'ana.anic', 'Tekuci racun',
    2, 1,
    150000.00, 150000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    250000.00, 1000000.00,
    0.00, 0.00,
    NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD';

-- =========================
-- JOVANA JOVANOVIC (client_id = 3)
-- STANDARDNI checking account, RSD
-- =========================
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
    0, 'CHECKING', '1110001300000000111',
    'Jovana', 'Jovanovic',
    'jovana.jovanovic@banka.com', 'jovana.jovanovic', 'Tekuci racun',
    3, 1,
    80000.00, 80000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    250000.00, 1000000.00,
    0.00, 0.00,
    NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD';

-- Jovana: EUR FX account, PERSONAL
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
    0, 'FX', '1110001300000000221',
    'Jovana', 'Jovanovic',
    NULL, NULL, 'Devizni racun EUR',
    3, 1,
    2000.00, 2000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    5000.00, 20000.00,
    0.00, 0.00,
    NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'EUR';

-- =========================
-- STEFAN STEFANOVIC (client_id = 4)
-- STANDARDNI checking account, RSD
-- =========================
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
    0, 'CHECKING', '1110001400000000111',
    'Stefan', 'Stefanovic',
    'stefan.stefanovic@banka.com', 'stefan.stefanovic', 'Tekuci racun',
    4, 1,
    200000.00, 200000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    250000.00, 1000000.00,
    0.00, 0.00,
    NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD';

-- Stefan: USD FX account, PERSONAL
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
    0, 'FX', '1110001400000000221',
    'Stefan', 'Stefanovic',
    NULL, NULL, 'Devizni racun USD',
    4, 1,
    3000.00, 3000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    5000.00, 20000.00,
    0.00, 0.00,
    NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'USD';

-- =========================
-- MILICA MILIC (client_id = 5)
-- STEDNI checking account, RSD
-- =========================
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
    0, 'CHECKING', '1110001500000000113',
    'Milica', 'Milic',
    'milica.milic@banka.com', 'milica.milic', 'Stedni racun',
    5, 1,
    50000.00, 50000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    250000.00, 1000000.00,
    0.00, 0.00,
    NULL, 'STEDNI', 200.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD';

-- =========================
-- NIKOLA NIKOLIC (client_id = 6)
-- STANDARDNI checking account, RSD
-- =========================
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
    0, 'CHECKING', '1110001600000000111',
    'Nikola', 'Nikolic',
    'nikola.nikolic@banka.com', 'nikola.nikolic', 'Tekuci racun',
    6, 1,
    300000.00, 300000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    250000.00, 1000000.00,
    0.00, 0.00,
    NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD';

-- Nikola: EUR FX account, PERSONAL
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
    0, 'FX', '1110001600000000221',
    'Nikola', 'Nikolic',
    NULL, NULL, 'Devizni racun EUR',
    6, 1,
    5000.00, 5000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    5000.00, 20000.00,
    0.00, 0.00,
    NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'EUR';

-- =========================
-- JELENA JELIC (client_id = 7)
-- ZA_MLADE checking account, RSD
-- =========================
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
    0, 'CHECKING', '1110001700000000115',
    'Jelena', 'Jelic',
    'jelena.jelic@banka.com', 'jelena.jelic', 'Racun za mlade',
    7, 1,
    25000.00, 25000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    250000.00, 1000000.00,
    0.00, 0.00,
    NULL, 'ZA_MLADE', 150.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD';

-- =========================
-- ALEKSANDAR ALEKSIC (client_id = 8)
-- STANDARDNI checking account, RSD
-- =========================
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
    0, 'CHECKING', '1110001800000000111',
    'Aleksandar', 'Aleksic',
    'aleksandar.aleksic@banka.com', 'aleksandar.aleksic', 'Tekuci racun',
    8, 1,
    500000.00, 500000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    250000.00, 1000000.00,
    0.00, 0.00,
    NULL, 'STANDARDNI', 255.00, NULL
FROM currency_table c WHERE c.oznaka = 'RSD';

-- Aleksandar: EUR FX account, PERSONAL
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
    0, 'FX', '1110001800000000221',
    'Aleksandar', 'Aleksic',
    NULL, NULL, 'Devizni racun EUR',
    8, 1,
    10000.00, 10000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    5000.00, 20000.00,
    0.00, 0.00,
    NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'EUR';

-- Aleksandar: USD FX account, PERSONAL
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
    0, 'FX', '1110001800000000321',
    'Aleksandar', 'Aleksic',
    NULL, NULL, 'Devizni racun USD',
    8, 1,
    5000.00, 5000.00,
    NOW(), '2031-03-25',
    c.id, 'ACTIVE',
    5000.00, 20000.00,
    0.00, 0.00,
    NULL, NULL, NULL, 'PERSONAL'
FROM currency_table c WHERE c.oznaka = 'USD';
