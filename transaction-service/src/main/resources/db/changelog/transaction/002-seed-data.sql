-- =========================
-- PAYMENT SEED DATA
-- Uses real account numbers from account-service seed (19-digit format)
--
-- Account reference:
--   1110001100000000111  Marko Markovic   (client 1) RSD CHECKING
--   1110001200000000111  Ana Anic         (client 2) RSD CHECKING
--   1110001300000000111  Jovana Jovanovic (client 3) RSD CHECKING
--   1110001300000000221  Jovana Jovanovic (client 3) EUR FX
--   1110001400000000111  Stefan Stefanovic(client 4) RSD CHECKING
--   1110001400000000221  Stefan Stefanovic(client 4) USD FX
--   1110001500000000113  Milica Milic     (client 5) RSD CHECKING
--   1110001600000000111  Nikola Nikolic   (client 6) RSD CHECKING
--   1110001600000000221  Nikola Nikolic   (client 6) EUR FX
--   1110001700000000115  Jelena Jelic     (client 7) RSD CHECKING
--   1110001800000000111  Aleksandar Aleksic(client 8) RSD CHECKING
--   1110001800000000221  Aleksandar Aleksic(client 8) EUR FX
--   1110001800000000321  Aleksandar Aleksic(client 8) USD FX
-- =========================

INSERT INTO payment_table (
    version, created_at, updated_at,
    order_number,
    from_account_number, to_account_number,
    initial_amount, final_amount, commission,
    sender_client_id, recipient_client_id, recipient_name,
    payment_code, reference_number, payment_purpose,
    status, from_currency, to_currency, exchange_rate
) VALUES

-- 1. Marko -> Ana, RSD, COMPLETED
(0, NOW() - INTERVAL '30 days', NOW() - INTERVAL '30 days',
 'ORD-2026-0001',
 '1110001100000000111', '1110001200000000111',
 15000.00, 14850.00, 150.00,
 1, 2, 'Ana Anic',
 '221', 'REF-001', 'Podela troskova stanarine',
 'COMPLETED', 'RSD', 'RSD', NULL),

-- 2. Ana -> Stefan, RSD, COMPLETED
(0, NOW() - INTERVAL '28 days', NOW() - INTERVAL '28 days',
 'ORD-2026-0002',
 '1110001200000000111', '1110001400000000111',
 8500.00, 8415.00, 85.00,
 2, 4, 'Stefan Stefanovic',
 '221', 'REF-002', 'Povracaj pozajmice',
 'COMPLETED', 'RSD', 'RSD', NULL),

-- 3. Stefan -> Jovana, RSD, COMPLETED
(0, NOW() - INTERVAL '25 days', NOW() - INTERVAL '25 days',
 'ORD-2026-0003',
 '1110001400000000111', '1110001300000000111',
 25000.00, 24750.00, 250.00,
 4, 3, 'Jovana Jovanovic',
 '289', 'REF-003', 'Uplata za zajednicki projekat',
 'COMPLETED', 'RSD', 'RSD', NULL),

-- 4. Nikola -> Milica, RSD, COMPLETED
(0, NOW() - INTERVAL '22 days', NOW() - INTERVAL '22 days',
 'ORD-2026-0004',
 '1110001600000000111', '1110001500000000113',
 5000.00, 4950.00, 50.00,
 6, 5, 'Milica Milic',
 '221', NULL, 'Poklonili smo se',
 'COMPLETED', 'RSD', 'RSD', NULL),

-- 5. Aleksandar -> Marko, RSD, COMPLETED
(0, NOW() - INTERVAL '20 days', NOW() - INTERVAL '20 days',
 'ORD-2026-0005',
 '1110001800000000111', '1110001100000000111',
 45000.00, 44550.00, 450.00,
 8, 1, 'Marko Markovic',
 '221', 'REF-005', 'Naknada za konsultacije',
 'COMPLETED', 'RSD', 'RSD', NULL),

-- 6. Milica -> Jelena, RSD, COMPLETED
(0, NOW() - INTERVAL '18 days', NOW() - INTERVAL '18 days',
 'ORD-2026-0006',
 '1110001500000000113', '1110001700000000115',
 3200.00, 3168.00, 32.00,
 5, 7, 'Jelena Jelic',
 '289', 'REF-006', 'Mesecna pretplata',
 'COMPLETED', 'RSD', 'RSD', NULL),

-- 7. Jovana (EUR) -> Nikola (EUR), EUR to EUR, COMPLETED
(0, NOW() - INTERVAL '15 days', NOW() - INTERVAL '15 days',
 'ORD-2026-0007',
 '1110001300000000221', '1110001600000000221',
 500.00, 495.00, 5.00,
 3, 6, 'Nikola Nikolic',
 '221', 'REF-007', 'Placanje za usluge',
 'COMPLETED', 'EUR', 'EUR', NULL),

-- 8. Stefan (USD) -> Aleksandar (USD), USD to USD, COMPLETED
(0, NOW() - INTERVAL '12 days', NOW() - INTERVAL '12 days',
 'ORD-2026-0008',
 '1110001400000000221', '1110001800000000321',
 1200.00, 1188.00, 12.00,
 4, 8, 'Aleksandar Aleksic',
 '221', 'REF-008', 'Refundacija troskova puta',
 'COMPLETED', 'USD', 'USD', NULL),

-- 9. Aleksandar (EUR) -> Jovana (EUR), EUR to EUR, COMPLETED
(0, NOW() - INTERVAL '10 days', NOW() - INTERVAL '10 days',
 'ORD-2026-0009',
 '1110001800000000221', '1110001300000000221',
 750.00, 742.50, 7.50,
 8, 3, 'Jovana Jovanovic',
 '221', 'REF-009', 'Uplata za kurs',
 'COMPLETED', 'EUR', 'EUR', NULL),

-- 10. Jelena -> Aleksandar, RSD, COMPLETED
(0, NOW() - INTERVAL '8 days', NOW() - INTERVAL '8 days',
 'ORD-2026-0010',
 '1110001700000000115', '1110001800000000111',
 12000.00, 11880.00, 120.00,
 7, 8, 'Aleksandar Aleksic',
 '289', 'REF-010', 'Kirija za studio',
 'COMPLETED', 'RSD', 'RSD', NULL),

-- 11. Marko (RSD) -> Nikola (RSD), DENIED
(0, NOW() - INTERVAL '6 days', NOW() - INTERVAL '6 days',
 'ORD-2026-0011',
 '1110001100000000111', '1110001600000000111',
 200000.00, 0.00, 0.00,
 1, 6, 'Nikola Nikolic',
 '221', NULL, 'Investicija',
 'DENIED', 'RSD', 'RSD', NULL),

-- 12. Nikola (RSD) -> Ana (RSD), COMPLETED
(0, NOW() - INTERVAL '4 days', NOW() - INTERVAL '4 days',
 'ORD-2026-0012',
 '1110001600000000111', '1110001200000000111',
 9800.00, 9702.00, 98.00,
 6, 2, 'Ana Anic',
 '221', 'REF-012', 'Povracaj duga',
 'COMPLETED', 'RSD', 'RSD', NULL),

-- 13. Ana -> Milica, RSD, IN_PROGRESS
(0, NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day',
 'ORD-2026-0013',
 '1110001200000000111', '1110001500000000113',
 6500.00, 6435.00, 65.00,
 2, 5, 'Milica Milic',
 '221', 'REF-013', 'Zajednicki vakcinalni fond',
 'IN_PROGRESS', 'RSD', 'RSD', NULL),

-- 14. Aleksandar (EUR) -> Stefan (USD), cross-currency, COMPLETED
-- EUR/USD rate approx 1.08
(0, NOW() - INTERVAL '3 days', NOW() - INTERVAL '3 days',
 'ORD-2026-0014',
 '1110001800000000221', '1110001400000000221',
 1000.00, 1068.00, 12.00,
 8, 4, 'Stefan Stefanovic',
 '221', 'REF-014', 'Placanje medjunarodnih usluga',
 'COMPLETED', 'EUR', 'USD', 1.08000000),

-- 15. Stefan -> Jelena, RSD, IN_PROGRESS
(0, NOW(), NOW(),
 'ORD-2026-0015',
 '1110001400000000111', '1110001700000000115',
 4500.00, 4455.00, 45.00,
 4, 7, 'Jelena Jelic',
 '289', 'REF-015', 'Uplata clanarine',
 'IN_PROGRESS', 'RSD', 'RSD', NULL);