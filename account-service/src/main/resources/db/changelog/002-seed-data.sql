-- =========================
-- CURRENCY SEED
-- =========================
INSERT INTO currency_table (naziv, oznaka, simbol, opis, status) VALUES
                                                                     ('Euro', 'EUR', '€', 'Evropska valuta', 'ACTIVE'),
                                                                     ('Swiss Franc', 'CHF', 'CHF', 'Švajcarski franak', 'ACTIVE'),
                                                                     ('US Dollar', 'USD', '$', 'Američki dolar', 'ACTIVE'),
                                                                     ('British Pound', 'GBP', '£', 'Britanska funta', 'ACTIVE'),
                                                                     ('Japanese Yen', 'JPY', '¥', 'Japanski jen', 'ACTIVE'),
                                                                     ('Canadian Dollar', 'CAD', 'CAD', 'Kanadski dolar', 'ACTIVE'),
                                                                     ('Australian Dollar', 'AUD', 'AUD', 'Australijski dolar', 'ACTIVE'),
                                                                     ('Serbian Dinar', 'RSD', 'RSD', 'Srpski dinar', 'ACTIVE');

-- =========================
-- CURRENCY COUNTRIES
-- =========================
INSERT INTO currency_countries (currency_id, country)
SELECT id, 'European Union' FROM currency_table WHERE oznaka = 'EUR';

INSERT INTO currency_countries (currency_id, country)
SELECT id, 'Switzerland' FROM currency_table WHERE oznaka = 'CHF';

INSERT INTO currency_countries (currency_id, country)
SELECT id, 'United States' FROM currency_table WHERE oznaka = 'USD';

INSERT INTO currency_countries (currency_id, country)
SELECT id, 'United Kingdom' FROM currency_table WHERE oznaka = 'GBP';

INSERT INTO currency_countries (currency_id, country)
SELECT id, 'Japan' FROM currency_table WHERE oznaka = 'JPY';

INSERT INTO currency_countries (currency_id, country)
SELECT id, 'Canada' FROM currency_table WHERE oznaka = 'CAD';

INSERT INTO currency_countries (currency_id, country)
SELECT id, 'Australia' FROM currency_table WHERE oznaka = 'AUD';

INSERT INTO currency_countries (currency_id, country)
SELECT id, 'Serbia' FROM currency_table WHERE oznaka = 'RSD';

-- =========================
-- SIFRA DELATNOSTI SEED
-- =========================
INSERT INTO sifra_delatnosti_table (sifra, grana) VALUES
                                                      ('1.11', 'Uzgoj žitarica i mahunarki'),
                                                      ('1.13', 'Uzgoj povrća'),
                                                      ('13.1', 'Priprema i predenje tekstilnih vlakana'),
                                                      ('24.1', 'Proizvodnja gvožđa i čelika'),
                                                      ('24.2', 'Proizvodnja čeličnih cevi'),
                                                      ('41.1', 'Razvoj građevinskih projekata'),
                                                      ('41.2', 'Izgradnja stambenih i nestambenih zgrada'),
                                                      ('42.11', 'Izgradnja puteva i autoputeva'),
                                                      ('42.12', 'Izgradnja železničkih i podzemnih pruga'),
                                                      ('42.13', 'Izgradnja mostova i tunela'),
                                                      ('42.21', 'Izgradnja vodovodnih projekata'),
                                                      ('42.22', 'Izgradnja elektroenergetskih mreža'),
                                                      ('5.1', 'Vađenje uglja'),
                                                      ('7.1', 'Vađenje gvozdenih ruda'),
                                                      ('8.11', 'Eksploatacija kamena'),
                                                      ('47.11', 'Trgovina na malo'),
                                                      ('56.1', 'Restorani i ugostiteljstvo'),
                                                      ('62.01', 'Računarsko programiranje'),
                                                      ('62.09', 'Ostale IT usluge'),
                                                      ('63.11', 'Obrada podataka i hosting'),
                                                      ('64.19', 'Ostale finansijske delatnosti'),
                                                      ('64.91', 'Finansijski lizing'),
                                                      ('65.11', 'Životno osiguranje'),
                                                      ('65.12', 'Neživotno osiguranje'),
                                                      ('66.21', 'Procena rizika i štete'),
                                                      ('68.1', 'Poslovanje nekretninama'),
                                                      ('53.1', 'Poštanske aktivnosti'),
                                                      ('53.2', 'Kurirske aktivnosti'),
                                                      ('85.1', 'Predškolsko obrazovanje'),
                                                      ('85.2', 'Osnovno obrazovanje'),
                                                      ('86.1', 'Bolničke aktivnosti'),
                                                      ('86.21', 'Opšta medicinska praksa'),
                                                      ('86.22', 'Specijalistička medicinska praksa'),
                                                      ('86.9', 'Ostale zdravstvene aktivnosti'),
                                                      ('84.12', 'Regulisanje delatnosti privrede'),
                                                      ('90.01', 'Delatnost pozorišta'),
                                                      ('90.02', 'Delatnost muzeja'),
                                                      ('90.04', 'Botanički i zoološki vrtovi'),
                                                      ('93.11', 'Sportski objekti'),
                                                      ('93.13', 'Delatnost teretana'),
                                                      ('93.19', 'Ostale sportske aktivnosti'),
                                                      ('26.11', 'Proizvodnja elektronskih komponenti'),
                                                      ('27.12', 'Proizvodnja električnih panela'),
                                                      ('29.1', 'Proizvodnja motornih vozila');

-- =========================
-- BANK INTERNAL ACCOUNTS
-- =========================

-- RSD bank account -> CHECKING
INSERT INTO account_table (
    version,
    account_type,
    broj_racuna,
    ime_vlasnika_racuna,
    prezime_vlasnika_racuna,
    naziv_racuna,
    vlasnik,
    zaposlen,
    stanje,
    raspolozivo_stanje,
    datum_i_vreme_kreiranja,
    datum_isteka,
    currency_id,
    status,
    dnevni_limit,
    mesecni_limit,
    dnevna_potrosnja,
    mesecna_potrosnja,
    company_id,
    account_concrete,
    odrzavanje_racuna,
    account_ownership_type
)
SELECT
    0,
    'CHECKING',
    '1110001000000000012',
    'Banka',
    'Banka',
    'Bank RSD Account',
    -1,
    -1,
    1000000000.00,
    1000000000.00,
    NOW(),
    NULL,
    c.id,
    'ACTIVE',
    999999999.99,
    999999999.99,
    0.00,
    0.00,
    NULL,
    'STANDARDNI',
    0.00,
    NULL
FROM currency_table c
WHERE c.oznaka = 'RSD';


-- FX bank accounts -> all non-RSD currencies
INSERT INTO account_table (
    version,
    account_type,
    broj_racuna,
    ime_vlasnika_racuna,
    prezime_vlasnika_racuna,
    naziv_racuna,
    vlasnik,
    zaposlen,
    stanje,
    raspolozivo_stanje,
    datum_i_vreme_kreiranja,
    datum_isteka,
    currency_id,
    status,
    dnevni_limit,
    mesecni_limit,
    dnevna_potrosnja,
    mesecna_potrosnja,
    company_id,
    account_concrete,
    odrzavanje_racuna,
    account_ownership_type
)
SELECT
    0,
    'FX',
    CASE c.oznaka
        WHEN 'EUR' THEN '1110001000000000021'
        WHEN 'CHF' THEN '1110001000000000022'
        WHEN 'USD' THEN '1110001000000000023'
        WHEN 'GBP' THEN '1110001000000000024'
        WHEN 'JPY' THEN '1110001000000000025'
        WHEN 'CAD' THEN '1110001000000000026'
        WHEN 'AUD' THEN '1110001000000000027'
        END,
    'Banka',
    'Banka',
    'Bank ' || c.oznaka || ' Account',
    -1,
    -1,
    1000000000.00,
    1000000000.00,
    NOW(),
    NULL,
    c.id,
    'ACTIVE',
    999999999.99,
    999999999.99,
    0.00,
    0.00,
    NULL,
    NULL,
    NULL,
    'BUSINESS'
FROM currency_table c
WHERE c.oznaka IN ('EUR', 'CHF', 'USD', 'GBP', 'JPY', 'CAD', 'AUD');



-- =========================
-- SIFRA DELATNOSTI SEKTORI
-- =========================
INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'Poljoprivreda' FROM sifra_delatnosti_table WHERE sifra IN ('1.11', '1.13');

INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'Industrija' FROM sifra_delatnosti_table WHERE sifra IN ('13.1', '24.1', '24.2', '26.11', '27.12', '29.1');

INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'Građevinarstvo' FROM sifra_delatnosti_table WHERE sifra LIKE '41%' OR sifra LIKE '42%';

INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'Rudarstvo' FROM sifra_delatnosti_table WHERE sifra IN ('5.1', '7.1', '8.11');

INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'Trgovina i ugostiteljstvo' FROM sifra_delatnosti_table WHERE sifra IN ('47.11', '56.1');

INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'IT' FROM sifra_delatnosti_table WHERE sifra LIKE '62%' OR sifra = '63.11';

INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'Finansije i osiguranje' FROM sifra_delatnosti_table WHERE sifra LIKE '64%' OR sifra LIKE '65%' OR sifra LIKE '66%';

INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'Nekretnine i logistika' FROM sifra_delatnosti_table WHERE sifra LIKE '68%' OR sifra LIKE '53%';

INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'Obrazovanje i zdravstvo' FROM sifra_delatnosti_table WHERE sifra LIKE '85%' OR sifra LIKE '86%';

INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'Kultura i sport' FROM sifra_delatnosti_table WHERE sifra LIKE '90%' OR sifra LIKE '93%';

INSERT INTO sifra_delatnosti_sektori (sifra_delatnosti_id, sektor)
SELECT id, 'Javna uprava' FROM sifra_delatnosti_table WHERE sifra LIKE '84%';