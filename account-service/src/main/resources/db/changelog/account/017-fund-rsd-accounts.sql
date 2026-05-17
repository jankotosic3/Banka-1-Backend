-- liquibase formatted sql

-- changeset account-service:017-fund-rsd-accounts context:dev
-- comment: DEV-ONLY normalize investment-fund settlement accounts to one RSD account per seeded fund.

UPDATE account_table
SET broj_racuna = '1110001999999911123',
    ime_vlasnika_racuna = 'SYSTEM',
    prezime_vlasnika_racuna = 'Investicioni fond: Konzervativni RSD',
    email = 'system+-1001@banka1.local',
    username = 'system--1001',
    naziv_racuna = 'Investicioni fond: Konzervativni RSD',
    vlasnik = -1001,
    zaposlen = -1,
    stanje = 25000.00,
    raspolozivo_stanje = 25000.00,
    status = 'ACTIVE',
    account_concrete = 'STANDARDNI'
WHERE broj_racuna IN ('1110001999999911', '1110001999999911123')
   OR vlasnik = -1001;

UPDATE account_table
SET broj_racuna = '1110001999999921123',
    ime_vlasnika_racuna = 'SYSTEM',
    prezime_vlasnika_racuna = 'Investicioni fond: Agresivni Tech RSD',
    email = 'system+-1002@banka1.local',
    username = 'system--1002',
    naziv_racuna = 'Investicioni fond: Agresivni Tech RSD',
    vlasnik = -1002,
    zaposlen = -1,
    stanje = 85000.00,
    raspolozivo_stanje = 85000.00,
    status = 'ACTIVE',
    account_concrete = 'STANDARDNI'
WHERE broj_racuna IN ('1110001999999921', '1110001999999921123')
   OR vlasnik = -1002;

UPDATE account_table
SET broj_racuna = '1110001999999931123',
    ime_vlasnika_racuna = 'SYSTEM',
    prezime_vlasnika_racuna = 'Investicioni fond: Dividendni RSD',
    email = 'system+-1003@banka1.local',
    username = 'system--1003',
    naziv_racuna = 'Investicioni fond: Dividendni RSD',
    vlasnik = -1003,
    zaposlen = -1,
    stanje = 5000.00,
    raspolozivo_stanje = 5000.00,
    status = 'ACTIVE',
    account_concrete = 'STANDARDNI'
WHERE broj_racuna IN ('1443062667343599319', '1110001999999931123')
   OR vlasnik = -1003;

UPDATE account_table
SET broj_racuna = '1110001999999941123',
    ime_vlasnika_racuna = 'SYSTEM',
    prezime_vlasnika_racuna = 'Investicioni fond: Likvidni Balans RSD',
    email = 'system+-1004@banka1.local',
    username = 'system--1004',
    naziv_racuna = 'Investicioni fond: Likvidni Balans RSD',
    vlasnik = -1004,
    zaposlen = -1,
    stanje = 500.00,
    raspolozivo_stanje = 500.00,
    status = 'ACTIVE',
    account_concrete = 'STANDARDNI'
WHERE broj_racuna IN ('9821290964588827910', '1110001999999941123')
   OR vlasnik = -1004;

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
SELECT 0, 'CHECKING', v.account_number,
       'SYSTEM', v.display_name,
       'system+' || v.owner_id || '@banka1.local', 'system-' || v.owner_id, v.display_name,
       v.owner_id, -1,
       v.balance, v.balance,
       NOW(), '2076-01-01',
       c.id, 'ACTIVE',
       999999999.99, 999999999.99,
       0.00, 0.00,
       NULL, 'STANDARDNI', 0.00, NULL
FROM currency_table c
JOIN (
    VALUES
        ('1110001999999911123', -1001, 'Investicioni fond: Konzervativni RSD', 25000.00),
        ('1110001999999921123', -1002, 'Investicioni fond: Agresivni Tech RSD', 85000.00),
        ('1110001999999931123', -1003, 'Investicioni fond: Dividendni RSD', 5000.00),
        ('1110001999999941123', -1004, 'Investicioni fond: Likvidni Balans RSD', 500.00)
) AS v(account_number, owner_id, display_name, balance)
  ON c.oznaka = 'RSD'
WHERE NOT EXISTS (
    SELECT 1 FROM account_table a WHERE a.broj_racuna = v.account_number
);
