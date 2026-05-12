-- liquibase formatted sql

-- changeset card-service:33-002-dev-mile-interbank-card context:dev
-- comment: PR_33 follow-up — DEV-ONLY seed za Mile Interbank Visa karticu.
--          Sluzi da na frontend-u (/cards-management i klijent /cards listing)
--          Mile odmah ima karticu — bez nje, prazna lista + neki ekrani padaju
--          na null array assumptions.
--          Account: 111000177777777711 (Mile RSD TEKUCI, vidi 013-mile-interbank-test-accounts.sql).
--          Brand: Visa (prefix 4, 16 cifara, Luhn-valid 4111111111111111).
--          CVV: dev placeholder hash (bcrypt('123') iz dev fixture-a).

INSERT INTO cards (version, deleted, card_number, card_type, card_name, creation_date,
                   expiration_date, account_number, cvv, card_limit, status, client_id)
SELECT 0, false, '4111111111111111', 'DEBIT', 'Mile Visa Klasik',
       CURRENT_DATE, (CURRENT_DATE + INTERVAL '4 years')::date,
       '111000177777777711',
       '$2a$10$dXJ3SXdlci9YeWlBeUx6Mi6Vk2H2KvKO4Ux6vKzkqHxYbHpAJq0aO',
       300000.00, 'ACTIVE', 15
WHERE NOT EXISTS (SELECT 1 FROM cards WHERE card_number='4111111111111111');
