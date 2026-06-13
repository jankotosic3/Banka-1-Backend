-- trading-service-go DEV seed (migration 002).
-- Concatenation of the Java Liquibase context:dev changesets the consolidated
-- trading-service applies (trading-otc/003..010). GATED: the runner applies this only
-- when LIQUIBASE_CONTEXTS contains 'dev' (compose default). Baseline-skipped on an
-- existing Liquibase DB. Idempotent (INSERT ... WHERE NOT EXISTS); refs market listing ids 1-8.

-- ============================================================================
-- source: trading-otc/003-dev-public-stock-seed.sql
-- ============================================================================
--liquibase formatted sql
-- changeset interbank:32-trading-003-dev-public-stock-seed context:dev
-- comment: DEV-ONLY rich seed za inter-bank handshake testiranje. Aktivira se
--          kad je LIQUIBASE_CONTEXTS=dev. Skipped u prod profilu.
--          Cilj: GET /public-stock vraca raznoliku listu tickera + prodavaca.

-- Public stock portfolios: 18 redova spread across 7 klijenata (id 1-7) i 8 tickera
-- (AAPL=1, MSFT=2, GOOGL=3, AMZN=4, TSLA=5, IBM=6, GS=7, JPM=8).
-- Quantity i publicQuantity su raznoliki da Tim 2 moze da pravi pregovore razlicitih
-- velicina. average_purchase_price je za izgled portfolija.

-- ============================================================================
-- Klijent 1 (Marko Markovic, C-1) — najveci portfolio, "whale" seller
-- ============================================================================
INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 1, 1, 'STOCK', 200, 175.5000, true, 100, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=1 AND listing_id=1);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 1, 2, 'STOCK', 150, 415.2500, true, 75, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=1 AND listing_id=2);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 1, 5, 'STOCK', 80, 245.0000, true, 50, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=1 AND listing_id=5);

-- ============================================================================
-- Klijent 2 (Ana Anic, C-2) — diversifikovan, srednji portfolio
-- ============================================================================
INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 2, 3, 'STOCK', 50, 142.7500, true, 25, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=2 AND listing_id=3);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 2, 4, 'STOCK', 30, 190.5000, true, 15, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=2 AND listing_id=4);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 2, 8, 'STOCK', 25, 220.0000, true, 10, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=2 AND listing_id=8);

-- ============================================================================
-- Klijent 3 (Jovana Jovanovic, C-3) — mali balansiran portfolio
-- ============================================================================
INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 3, 1, 'STOCK', 40, 180.0000, true, 20, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=3 AND listing_id=1);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 3, 6, 'STOCK', 60, 165.5000, true, 30, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=3 AND listing_id=6);

-- ============================================================================
-- Klijent 4 (Stefan Stefanovic, C-4) — fokus na finansije
-- ============================================================================
INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 4, 7, 'STOCK', 35, 425.0000, true, 20, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=4 AND listing_id=7);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 4, 8, 'STOCK', 45, 215.0000, true, 25, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=4 AND listing_id=8);

-- ============================================================================
-- Klijent 5 (Milica Milic, C-5) — tech-heavy, AAPL whale (Tim 2 spec primer)
-- ============================================================================
INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 5, 1, 'STOCK', 100, 178.2500, true, 80, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=5 AND listing_id=1);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 5, 2, 'STOCK', 60, 412.5000, true, 40, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=5 AND listing_id=2);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 5, 3, 'STOCK', 35, 144.5000, true, 25, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=5 AND listing_id=3);

-- ============================================================================
-- Klijent 6 (Nikola Nikolic, C-6) — privatni portfolio (NIJE javan, kontrol)
-- ============================================================================
-- Ovaj NE postavlja public — testira da li GET /public-stock pravilno filtrira
INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 6, 4, 'STOCK', 50, 188.0000, false, 0, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=6 AND listing_id=4);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 6, 5, 'STOCK', 20, 248.0000, false, 0, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=6 AND listing_id=5);

-- ============================================================================
-- Klijent 7 (Jelena Jelic, C-7) — small positions, multi-ticker
-- ============================================================================
INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 7, 2, 'STOCK', 15, 418.0000, true, 10, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=7 AND listing_id=2);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 7, 4, 'STOCK', 20, 191.5000, true, 12, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=7 AND listing_id=4);

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 7, 7, 'STOCK', 18, 428.0000, true, 12, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=7 AND listing_id=7);

-- ============================================================================
-- Sumarni snapshot za debugging — koliko public stocks ima na kraju
-- ============================================================================
-- Expected output: 16 javnih portfolio redova (svi osim user_id=6 koji ima 2 privatna).
-- GET /public-stock kroz interbank-service ce po tikeru agregirati prodavce:
--   AAPL (C-1=100, C-3=20, C-5=80) — 3 prodavca, ukupno 200 jedinica
--   MSFT (C-1=75, C-5=40, C-7=10)  — 3 prodavca, 125 jedinica
--   GOOGL (C-2=25, C-5=25)         — 2 prodavca, 50 jedinica
--   AMZN (C-2=15, C-7=12)          — 2 prodavca, 27 jedinica
--   TSLA (C-1=50)                   — 1 prodavac, 50 jedinica
--   IBM (C-3=30)                    — 1 prodavac, 30 jedinica
--   GS (C-4=20, C-7=12)             — 2 prodavca, 32 jedinica
--   JPM (C-2=10, C-4=25)            — 2 prodavca, 35 jedinica
-- Plus 1 actuary (E-1, Admin) ima jos jedan portfolio za supervisor flow:
INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 1, 4, 'STOCK', 25, 192.0000, true, 15, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=1 AND listing_id=4);


-- ============================================================================
-- source: trading-otc/004-dev-mile-interbank-portfolio.sql
-- ============================================================================
--liquibase formatted sql
-- changeset interbank:32-trading-004-mile-interbank-portfolio context:dev
-- comment: PR_32 Phase 17b — Mile Interbank (C-15) AAPL portfolio za handshake
--          paritet sa Tim 2 (oni dodali svog Mile C-6 sa 100 AAPL public 25).
--          Tim 2 ce kao buyer slati POST /negotiations sa
--          sellerId={111, "C-15"}, pa nas seed mora da ima 100 AAPL javnih 25.
--
--          NAPOMENA: Mile-ov client_id je 15 (ne 11) jer 009-distinct-test-clients.sql
--          je dodao 5 dodatnih klijenata (Mira C-11, Dejan C-12, Tijana C-13,
--          Ognjen C-14, Petar C-10) pre nego sto 010-mile-interbank dodaje Mile.
--          Trading-service je drugacija baza pa cross-db SELECT FROM clients ne radi —
--          hardkode-ovan ID. Ako sledeci PR doda klijenta izmedju 009 i 010,
--          azurirati ovaj ID + dokumentaciju.

INSERT INTO portfolio (user_id, listing_id, listing_type, quantity, average_purchase_price,
                       is_public, public_quantity, last_modified, reserved_quantity)
SELECT 15, 1, 'STOCK', 100, 178.5000, true, 25, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM portfolio WHERE user_id=15 AND listing_id=1);

-- Sumarno za AAPL posle ovog seed-a (totalna javna AAPL kolicina u Banka 1):
--   C-1 (Marko)  — 100 javnih
--   C-3 (Jovana) —  20 javnih
--   C-5 (Milica) —  80 javnih
--   C-15 (Mile)  —  25 javnih  ← NOVI dedicated test target
--   = 4 seller-a, ukupno 225 javnih AAPL

-- ============================================================================
-- source: trading-otc/005-dev-intra-bank-otc-seed.sql
-- ============================================================================
--liquibase formatted sql

-- changeset interbank:33-trading-005-dev-intra-bank-otc-seed context:dev
-- comment: PR_33 follow-up — INTRA-bank OTC seed za demo/testiranje sa frontend-a.
--          Mile Interbank (user_id=15, "C-15") postavlja se u SVE 4 uloge da bi mogao
--          da testira ceo OTC lifecycle iz UI-a (offers, contracts, exercise).
--          Plus 2 vec sklopljena ugovora (ACCEPTED + ACTIVE option contract) da
--          contracts tab ima sta da prikaze.
--
--          User mapping (out of 003-dev-public-stock-seed + Mile 004):
--            1 = Marko Markovic    (AAPL 200, MSFT 150, TSLA 80, AMZN 25)
--            2 = Ana Anic          (GOOGL 50, AMZN 30, JPM 25)
--            3 = Milica Milic      (AAPL 40, IBM 60)
--            5 = Jovana Jovanovic  (AAPL 100, MSFT 60, GOOGL 35)
--           15 = Mile Interbank    (AAPL 100, public 25)
--
--          Tickers iz market seed-a: AAPL, MSFT, GOOGL, AMZN, TSLA, IBM, GS, JPM.
--          Stock prices su "danasnje" ~realne vrednosti (~Q1 2026).
--
--          Statusi:
--            PENDING_BUYER  — ceka kupca da odgovori (counter ili accept)
--            PENDING_SELLER — ceka prodavca da odgovori
--            ACCEPTED       — sklopljen ugovor (option_contracts row prati)
--            REJECTED       — odbijena ponuda

-- ============================================================================
-- Scenario 1: Mile (15) ponudio Marko-u (1) da PRODA AAPL × 10 @ 195 USD
--             Status PENDING_BUYER — Marko (kupac) je na redu da odgovori.
--             Mile sad iz UI-a: vidi ponudu u "Aktivne ponude" tab, ali je `Status: PENDING_BUYER`
--             tj. ceka drugu stranu. Nema accept/counter dugmadi za Mile-a (on je
--             vec napravio ponudu).
-- ============================================================================
INSERT INTO otc_offers (stock_ticker, buyer_id, seller_id, amount, price_per_stock, premium,
                        settlement_date, status, modified_by, last_modified, created_at, version)
SELECT 'AAPL', 1, 15, 10, 195.00, 80.00,
       (CURRENT_DATE + INTERVAL '14 days')::date, 'PENDING_BUYER', 'SELLER:15',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
WHERE NOT EXISTS (SELECT 1 FROM otc_offers
                  WHERE stock_ticker='AAPL' AND seller_id=15 AND buyer_id=1
                    AND price_per_stock=195.00 AND amount=10);

-- ============================================================================
-- Scenario 2: Marko (1) ponudio Mile-u (15) da KUPI AAPL × 5 @ 198 USD
--             Status PENDING_SELLER — Marko (prodavac) je na redu (counter-offer
--             u toku). Mile sad ne treba da odgovara.
--             Wait — ovo je gresno semanticki: ako Marko prodaje, on je seller.
--             Status PENDING_SELLER znaci da seller treba da odgovori (counter).
--             Znaci Marko sebi proba prodaju Mile-u, sad on (seller) na redu da
--             potvrdi/counter-uje sa svoje strane (npr. posle Mile counter-offer-a).
--             Mile vidi: PENDING_SELLER, ne moze da accept/counter dok seller ne odgovori.
-- ============================================================================
INSERT INTO otc_offers (stock_ticker, buyer_id, seller_id, amount, price_per_stock, premium,
                        settlement_date, status, modified_by, last_modified, created_at, version)
SELECT 'AAPL', 15, 1, 5, 198.00, 50.00,
       (CURRENT_DATE + INTERVAL '21 days')::date, 'PENDING_SELLER', 'BUYER:15',
       CURRENT_TIMESTAMP - INTERVAL '2 hours', CURRENT_TIMESTAMP - INTERVAL '1 day', 0
WHERE NOT EXISTS (SELECT 1 FROM otc_offers
                  WHERE stock_ticker='AAPL' AND seller_id=1 AND buyer_id=15
                    AND price_per_stock=198.00 AND amount=5);

-- ============================================================================
-- Scenario 3: Ana (2) ponudila Mile-u (15) da KUPI GOOGL × 5 @ 145 USD
--             Status PENDING_SELLER — Mile (seller) na redu da odgovori.
--             Ovo je glavni demo scenario — Mile moze klik Prihvati ili Protivponuda
--             ili Odustani u UI-u.
--             Mile NEMA GOOGL portfolio! Posto je intra-bank, ako prihvati,
--             trading-service ce traziti GOOGL u Mile portfoliu i pasti.
--             Resenje: kupac je 15 (Mile), seller je 2 (Ana). Ana ima GOOGL 50.
--             Drugacije: Ana prodaje Mile-u, Ana je seller. Mile je kupac. Mile ne mora
--             da ima GOOGL — kupuje opciju da kupi GOOGL od Ane na settlement date.
--             OTC opcija = pravo, ne obaveza, da kupi/proda. Premium se placa
--             odmah (Mile placa Ani premium 35 USD da rezervise pravo).
--
--             Frontend buyer_id=15 → "PENDING_SELLER" znaci seller (Ana) na redu.
--             Hmm sad cekam koji status implicitno znaci "Mile na redu".
--             Per OtcServiceImpl: offer kreira buyer ili seller. modified_by polje
--             pokazuje ko je poslednji menjao. status flip-uje koji je sledeci na redu.
--             Da Mile bude na redu kao seller, status=PENDING_SELLER, buyer_id=2,
--             seller_id=15.
-- ============================================================================
INSERT INTO otc_offers (stock_ticker, buyer_id, seller_id, amount, price_per_stock, premium,
                        settlement_date, status, modified_by, last_modified, created_at, version)
SELECT 'AAPL', 2, 15, 8, 192.50, 60.00,
       (CURRENT_DATE + INTERVAL '10 days')::date, 'PENDING_SELLER', 'BUYER:2',
       CURRENT_TIMESTAMP - INTERVAL '4 hours', CURRENT_TIMESTAMP - INTERVAL '4 hours', 0
WHERE NOT EXISTS (SELECT 1 FROM otc_offers
                  WHERE stock_ticker='AAPL' AND seller_id=15 AND buyer_id=2
                    AND price_per_stock=192.50 AND amount=8);

-- ============================================================================
-- Scenario 4: Mile (15) trazi da KUPI MSFT × 3 od Jovana (5)
--             Status PENDING_BUYER — Mile (buyer) na redu.
--             Jovana je counter-ovala, sad Mile bira: accept ili counter ili odustani.
-- ============================================================================
INSERT INTO otc_offers (stock_ticker, buyer_id, seller_id, amount, price_per_stock, premium,
                        settlement_date, status, modified_by, last_modified, created_at, version)
SELECT 'MSFT', 15, 5, 3, 420.00, 120.00,
       (CURRENT_DATE + INTERVAL '30 days')::date, 'PENDING_BUYER', 'SELLER:5',
       CURRENT_TIMESTAMP - INTERVAL '6 hours', CURRENT_TIMESTAMP - INTERVAL '2 days', 0
WHERE NOT EXISTS (SELECT 1 FROM otc_offers
                  WHERE stock_ticker='MSFT' AND seller_id=5 AND buyer_id=15
                    AND price_per_stock=420.00 AND amount=3);

-- ============================================================================
-- Scenario 5: ACCEPTED + ACTIVE option contract — Mile (15) je kupio AAPL
--             opciju od Marko-a (1), premium 100 USD placen, contract aktivan.
--             U UI Contracts tab-u Mile-a treba da vidi ovaj ugovor sa dugmetom
--             "Iskoristi (Exercise)".
-- ============================================================================
INSERT INTO otc_offers (id, stock_ticker, buyer_id, seller_id, amount, price_per_stock, premium,
                        settlement_date, status, modified_by, last_modified, created_at, version)
SELECT 9001, 'AAPL', 15, 1, 5, 190.00, 100.00,
       (CURRENT_DATE + INTERVAL '60 days')::date, 'ACCEPTED', 'SELLER:1',
       CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '3 days', 0
WHERE NOT EXISTS (SELECT 1 FROM otc_offers WHERE id=9001);

-- Bump sequence iznad 9001 (ako naknadne ponude koriste serial, da ne kolizija).
SELECT setval(pg_get_serial_sequence('otc_offers','id'),
              GREATEST((SELECT MAX(id) FROM otc_offers), 9100));

INSERT INTO option_contracts (offer_id, stock_ticker, buyer_id, seller_id, amount,
                              price_per_stock, settlement_date, status,
                              created_at, version)
SELECT 9001, 'AAPL', 15, 1, 5, 190.00,
       (CURRENT_DATE + INTERVAL '60 days')::date, 'ACTIVE',
       CURRENT_TIMESTAMP - INTERVAL '1 day', 0
WHERE NOT EXISTS (SELECT 1 FROM option_contracts WHERE offer_id=9001);

-- ============================================================================
-- Scenario 28: Kupac ne iskorišćava opciju — ugovor ističe, kupac gubi premiju.
--              ACTIVE ugovor sa settlement_date u prošlosti (juče). Kada
--              ExpireOverdueContracts scheduler odradi, status postaje EXPIRED
--              i prodavčeve rezervisane akcije se oslobađaju. Premija ostaje
--              kod prodavca — kupac (Mile, id=15) gubi plaćeni iznos.
--              OTC_SCHEDULERS_ENABLED=true mora biti postavljeno u okruženju.
-- ============================================================================
INSERT INTO otc_offers (id, stock_ticker, buyer_id, seller_id, amount, price_per_stock, premium,
                        settlement_date, status, modified_by, last_modified, created_at, version)
SELECT 9002, 'NVDA', 15, 1, 3, 850.00, 75.00,
       (CURRENT_DATE - INTERVAL '1 day')::date, 'ACCEPTED', 'SELLER:1',
       CURRENT_TIMESTAMP - INTERVAL '31 days', CURRENT_TIMESTAMP - INTERVAL '32 days', 0
WHERE NOT EXISTS (SELECT 1 FROM otc_offers WHERE id=9002);

INSERT INTO option_contracts (offer_id, stock_ticker, buyer_id, seller_id, amount,
                              price_per_stock, settlement_date, status,
                              created_at, version)
SELECT 9002, 'NVDA', 15, 1, 3, 850.00,
       (CURRENT_DATE - INTERVAL '1 day')::date, 'ACTIVE',
       CURRENT_TIMESTAMP - INTERVAL '31 days', 0
WHERE NOT EXISTS (SELECT 1 FROM option_contracts WHERE offer_id=9002);

-- ============================================================================
-- Scenario 6: REJECTED ponuda u istoriji — Mile (15) je odbio MSFT ponudu od Ane (2)
--             Pokriva "Istorija" tab/sortiranje po datumu.
-- ============================================================================
INSERT INTO otc_offers (stock_ticker, buyer_id, seller_id, amount, price_per_stock, premium,
                        settlement_date, status, modified_by, last_modified, created_at, version)
SELECT 'MSFT', 2, 15, 2, 425.00, 50.00,
       (CURRENT_DATE + INTERVAL '7 days')::date, 'REJECTED', 'SELLER:15',
       CURRENT_TIMESTAMP - INTERVAL '12 hours', CURRENT_TIMESTAMP - INTERVAL '2 days', 0
WHERE NOT EXISTS (SELECT 1 FROM otc_offers
                  WHERE stock_ticker='MSFT' AND seller_id=15 AND buyer_id=2 AND status='REJECTED');

-- ============================================================================
-- Bonus: 2 ponude izmedju drugih klijenata (NE Mile) — ekran "Aktivne ponude"
-- nije prazan kad se loguje neko drugi (npr. Marko = id 1) sa testne strane.
-- ============================================================================
INSERT INTO otc_offers (stock_ticker, buyer_id, seller_id, amount, price_per_stock, premium,
                        settlement_date, status, modified_by, last_modified, created_at, version)
SELECT 'TSLA', 3, 1, 5, 250.00, 75.00,
       (CURRENT_DATE + INTERVAL '15 days')::date, 'PENDING_BUYER', 'SELLER:1',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP - INTERVAL '4 hours', 0
WHERE NOT EXISTS (SELECT 1 FROM otc_offers
                  WHERE stock_ticker='TSLA' AND seller_id=1 AND buyer_id=3
                    AND price_per_stock=250.00 AND amount=5);

INSERT INTO otc_offers (stock_ticker, buyer_id, seller_id, amount, price_per_stock, premium,
                        settlement_date, status, modified_by, last_modified, created_at, version)
SELECT 'GOOGL', 5, 2, 4, 148.00, 40.00,
       (CURRENT_DATE + INTERVAL '20 days')::date, 'PENDING_SELLER', 'BUYER:5',
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP - INTERVAL '8 hours', 0
WHERE NOT EXISTS (SELECT 1 FROM otc_offers
                  WHERE stock_ticker='GOOGL' AND seller_id=2 AND buyer_id=5
                    AND price_per_stock=148.00 AND amount=4);

-- ============================================================================
-- source: trading-otc/006-dev-fund-seed.sql
-- ============================================================================
--liquibase formatted sql

-- changeset interbank:33-trading-006-dev-fund-seed context:dev
-- comment: PR_33 follow-up — DEV-ONLY investicioni fond + Mile-ova pozicija u njemu.
--          Sluzi da Fund discovery + "Moji fondovi" ekrani na frontend-u imaju
--          podatke za prikaz, umesto prazne liste koja moze izgledati kao bug.
--
--          Fond: "Konzervativni RSD" — minimum_contribution 5000 RSD, manager
--          je employee Petar Petrovic (id=2 iz employee 002-seed-data.sql,
--          SUPERVISOR, broker tim).
--
--          Mile Interbank (client_id=15) ima 75000 RSD ulozeno (total_invested).
--          Plus jedan fund_holdings red (AAPL × 10 prosecna 180 USD) — da
--          ukupna vrednost fonda > samo likvidna_sredstva (testira fund value
--          aggregator put fix-a iz PR_30 KRIT 6).

INSERT INTO investment_funds (naziv, opis, minimum_contribution, manager_id,
                              likvidna_sredstva, account_number, datum_kreiranja,
                              deleted, created_at, version)
SELECT 'Konzervativni RSD',
       'Niska volatilnost, RSD bazna valuta. Pretezno bankarski depoziti + odabrane stabilne akcije sa NYSE/NASDAQ-a.',
       5000.00, 2, 250000.00, '1110001999999911', CURRENT_DATE - INTERVAL '90 days',
       false, CURRENT_TIMESTAMP - INTERVAL '90 days', 0
WHERE NOT EXISTS (SELECT 1 FROM investment_funds WHERE naziv='Konzervativni RSD');

-- Drugi fond za varietet (Fund discovery treba bar 2 reda da filter/sort imaju smisla).
INSERT INTO investment_funds (naziv, opis, minimum_contribution, manager_id,
                              likvidna_sredstva, account_number, datum_kreiranja,
                              deleted, created_at, version)
SELECT 'Agresivni Tech USD',
       'Visok rizik, koncentracija na FAANG + EV. USD denominovan, hedzovano protiv RSD pomeranja.',
       50000.00, 2, 180000.00, '1110001999999921', CURRENT_DATE - INTERVAL '45 days',
       false, CURRENT_TIMESTAMP - INTERVAL '45 days', 0
WHERE NOT EXISTS (SELECT 1 FROM investment_funds WHERE naziv='Agresivni Tech USD');

-- Mile pozicija u "Konzervativni RSD" fondu — uloze 75000 RSD pre 30 dana.
INSERT INTO client_fund_positions (client_id, fund_id, total_invested, first_invested_at,
                                    last_modified_at, version)
SELECT 15, f.id, 75000.00,
       CURRENT_TIMESTAMP - INTERVAL '30 days', CURRENT_TIMESTAMP - INTERVAL '30 days', 0
FROM investment_funds f
WHERE f.naziv = 'Konzervativni RSD'
  AND NOT EXISTS (SELECT 1 FROM client_fund_positions WHERE client_id=15 AND fund_id=f.id);

-- Marko (client_id=1) takodje u tom fondu — vise klijenata u fondu testira agregaciju.
INSERT INTO client_fund_positions (client_id, fund_id, total_invested, first_invested_at,
                                    last_modified_at, version)
SELECT 1, f.id, 150000.00,
       CURRENT_TIMESTAMP - INTERVAL '60 days', CURRENT_TIMESTAMP - INTERVAL '5 days', 0
FROM investment_funds f
WHERE f.naziv = 'Konzervativni RSD'
  AND NOT EXISTS (SELECT 1 FROM client_fund_positions WHERE client_id=1 AND fund_id=f.id);

-- Holding fonda: 10 AAPL @ avg 180 USD (to napumpa totalValue iznad likvidna_sredstva
-- jednom kad InvestmentFundService bude koristio MarketPriceClient — PR_34 kandidat).
INSERT INTO fund_holdings (fund_id, stock_ticker, quantity, avg_unit_price, deleted,
                            created_at, version)
SELECT f.id, 'AAPL', 10, 180.0000, false, CURRENT_TIMESTAMP, 0
FROM investment_funds f
WHERE f.naziv = 'Konzervativni RSD'
  AND NOT EXISTS (SELECT 1 FROM fund_holdings WHERE fund_id=f.id AND stock_ticker='AAPL');

-- Client fund transaction istorija za Mile-a — COMPLETED uplate.
INSERT INTO client_fund_transactions (client_id, fund_id, amount, is_inflow, status,
                                       occurred_at, client_account_number)
SELECT 15, f.id, 75000.00, true, 'COMPLETED',
       CURRENT_TIMESTAMP - INTERVAL '30 days', '1110001777777777'
FROM investment_funds f
WHERE f.naziv = 'Konzervativni RSD'
  AND NOT EXISTS (SELECT 1 FROM client_fund_transactions WHERE client_id=15 AND fund_id=f.id);

-- ============================================================================
-- source: trading-otc/008-fund-seed-consistency.sql
-- ============================================================================
--liquibase formatted sql

-- changeset trading-service:008-fund-seed-consistency context:dev
-- comment: DEV-ONLY cleanup for investment-fund fixtures used by fund invest/redeem tests.

ALTER TABLE client_fund_transactions
    ALTER COLUMN client_account_number TYPE VARCHAR(50);

UPDATE investment_funds
SET minimum_contribution = 1000.00,
    likvidna_sredstva = 5000.00
WHERE naziv = 'Konzervativni RSD';

UPDATE fund_holdings h
SET quantity = 1000,
    avg_unit_price = 180.0000,
    deleted = false
FROM investment_funds f
WHERE h.fund_id = f.id
  AND f.naziv = 'Konzervativni RSD'
  AND h.stock_ticker = 'AAPL';

INSERT INTO fund_holdings (fund_id, stock_ticker, quantity, avg_unit_price, deleted, created_at, version)
SELECT f.id, 'AAPL', 1000, 180.0000, false, CURRENT_TIMESTAMP, 0
FROM investment_funds f
WHERE f.naziv = 'Konzervativni RSD'
  AND NOT EXISTS (
      SELECT 1 FROM fund_holdings h WHERE h.fund_id = f.id AND h.stock_ticker = 'AAPL'
  );

UPDATE client_fund_transactions
SET client_account_number = '111000177777777711'
WHERE client_id = 15
  AND client_account_number = '1110001777777777';

INSERT INTO client_fund_transactions (client_id, fund_id, amount, is_inflow, status,
                                      occurred_at, client_account_number)
SELECT 1, f.id, 150000.00, true, 'COMPLETED',
       CURRENT_TIMESTAMP - INTERVAL '60 days', '1110001100000000111'
FROM investment_funds f
WHERE f.naziv = 'Konzervativni RSD'
  AND NOT EXISTS (
      SELECT 1 FROM client_fund_transactions tx
      WHERE tx.client_id = 1 AND tx.fund_id = f.id AND tx.is_inflow = true
  );

-- ============================================================================
-- source: trading-otc/009-fund-account-number-length.sql
-- ============================================================================
--liquibase formatted sql

-- changeset trading-service:009-fund-account-number-length context:dev
-- comment: DEV-ONLY fund account numbers must satisfy account debit/credit validation.

ALTER TABLE investment_funds
    DROP CONSTRAINT IF EXISTS investment_funds_account_number_check;

ALTER TABLE investment_funds
    ALTER COLUMN account_number TYPE VARCHAR(50);

UPDATE investment_funds
SET account_number = '1110001999999911123'
WHERE naziv = 'Konzervativni RSD';

UPDATE investment_funds
SET account_number = '1110001999999921123'
WHERE naziv = 'Agresivni Tech USD';

-- ============================================================================
-- source: trading-otc/010-fund-seed-rsd-multi-funds.sql
-- ============================================================================
--liquibase formatted sql

-- changeset trading-service:010-fund-seed-rsd-multi-funds context:dev
-- comment: DEV-ONLY normalize investment funds to four RSD funds with one RSD settlement account each.

UPDATE investment_funds
SET naziv = 'Konzervativni RSD',
    opis = 'Niska volatilnost, RSD bazna valuta. Stabilne blue-chip pozicije uz visok gotovinski deo.',
    minimum_contribution = 1000.00,
    manager_id = 2,
    likvidna_sredstva = 25000.00,
    account_number = '1110001999999911123',
    deleted = false
WHERE naziv = 'Konzervativni RSD';

UPDATE investment_funds
SET naziv = 'Agresivni Tech RSD',
    opis = 'RSD fond visokog rizika sa koncentracijom na tehnoloske akcije.',
    minimum_contribution = 50000.00,
    manager_id = 2,
    likvidna_sredstva = 85000.00,
    account_number = '1110001999999921123',
    deleted = false
WHERE naziv IN ('Agresivni Tech USD', 'Agresivni Tech RSD');

UPDATE investment_funds
SET naziv = 'Dividendni RSD',
    opis = 'RSD fond srednjeg rizika sa naglaskom na profitabilne, zrele kompanije.',
    minimum_contribution = 10000.00,
    manager_id = 3,
    likvidna_sredstva = 5000.00,
    account_number = '1110001999999931123',
    deleted = false
WHERE naziv IN ('Scenario 38 Test Fund 20260516', 'Dividendni RSD');

INSERT INTO investment_funds (naziv, opis, minimum_contribution, manager_id,
                              likvidna_sredstva, account_number, datum_kreiranja,
                              deleted, created_at, version)
SELECT 'Dividendni RSD',
       'RSD fond srednjeg rizika sa naglaskom na profitabilne, zrele kompanije.',
       10000.00, 3, 5000.00, '1110001999999931123', CURRENT_DATE - INTERVAL '30 days',
       false, CURRENT_TIMESTAMP - INTERVAL '30 days', 0
WHERE NOT EXISTS (SELECT 1 FROM investment_funds WHERE naziv = 'Dividendni RSD');

UPDATE investment_funds
SET naziv = 'Likvidni Balans RSD',
    opis = 'RSD fond sa vrlo niskom trenutnom likvidnoscu, namenjen testiranju odbijanja i likvidacije.',
    minimum_contribution = 500.00,
    manager_id = 4,
    likvidna_sredstva = 500.00,
    account_number = '1110001999999941123',
    deleted = false
WHERE naziv IN ('Scenario 38 Fixed Fund 20260516', 'Likvidni Balans RSD');

INSERT INTO investment_funds (naziv, opis, minimum_contribution, manager_id,
                              likvidna_sredstva, account_number, datum_kreiranja,
                              deleted, created_at, version)
SELECT 'Likvidni Balans RSD',
       'RSD fond sa vrlo niskom trenutnom likvidnoscu, namenjen testiranju odbijanja i likvidacije.',
       500.00, 4, 500.00, '1110001999999941123', CURRENT_DATE - INTERVAL '15 days',
       false, CURRENT_TIMESTAMP - INTERVAL '15 days', 0
WHERE NOT EXISTS (SELECT 1 FROM investment_funds WHERE naziv = 'Likvidni Balans RSD');

DELETE FROM fund_holdings
WHERE fund_id IN (
    SELECT id
    FROM investment_funds
    WHERE naziv IN ('Konzervativni RSD', 'Agresivni Tech RSD', 'Dividendni RSD', 'Likvidni Balans RSD')
);

INSERT INTO fund_holdings (fund_id, stock_ticker, quantity, avg_unit_price, deleted, created_at, version)
SELECT f.id, v.stock_ticker, v.quantity, v.avg_unit_price, false, CURRENT_TIMESTAMP, 0
FROM investment_funds f
JOIN (
    VALUES
        ('Konzervativni RSD', 'AAPL', 120, 180.0000),
        ('Konzervativni RSD', 'BAC', 500, 45.0000),
        ('Agresivni Tech RSD', 'MSFT', 80, 410.0000),
        ('Agresivni Tech RSD', 'TSLA', 40, 250.0000),
        ('Dividendni RSD', 'GOOGL', 30, 140.0000),
        ('Dividendni RSD', 'AMZN', 60, 170.0000),
        ('Likvidni Balans RSD', 'JPM', 25, 220.0000),
        ('Likvidni Balans RSD', 'IBM', 40, 180.0000),
        ('Likvidni Balans RSD', 'WMT', 75, 90.0000)
) AS v(fund_name, stock_ticker, quantity, avg_unit_price)
  ON f.naziv = v.fund_name;

-- ============================================================================
-- source: celina3-mobile-seed — orders for Marko Markovic (user_id=1)
-- ============================================================================
-- 4 orders in various states so the mobile "Moji Nalozi" screen is non-empty.
-- account_id=18 is Marko's RSD checking account in banking-core (id=18 after
-- the deterministic dev seed: ~9 system accounts + 9 client accounts before his).
-- listing ids: 1=AAPL, 2=MSFT, 3=GOOGL, 5=TSLA (from market seed).

INSERT INTO orders (
    user_id, listing_id, order_type, quantity, contract_size,
    price_per_unit, limit_value, stop_value, direction, status,
    approved_by, is_done, last_modification, remaining_portions,
    after_hours, all_or_none, margin, account_id, exchange_closed,
    reserved_limit_exposure
)
SELECT 1, 1, 'MARKET', 10, 1, 311.23, NULL, NULL, 'BUY', 'DONE',
       NULL, true, NOW()-INTERVAL '20 days', 0,
       false, false, false, 18, false, 0
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE user_id=1 AND listing_id=1 AND status='DONE' AND direction='BUY' AND quantity=10);

INSERT INTO orders (
    user_id, listing_id, order_type, quantity, contract_size,
    price_per_unit, limit_value, stop_value, direction, status,
    approved_by, is_done, last_modification, remaining_portions,
    after_hours, all_or_none, margin, account_id, exchange_closed,
    reserved_limit_exposure
)
SELECT 1, 2, 'LIMIT', 20, 1, 415.00, 415.00, NULL, 'BUY', 'PARTIALLY_FILLED',
       NULL, false, NOW()-INTERVAL '10 days', 12,
       false, false, false, 18, false, 0
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE user_id=1 AND listing_id=2 AND status='PARTIALLY_FILLED');

INSERT INTO orders (
    user_id, listing_id, order_type, quantity, contract_size,
    price_per_unit, limit_value, stop_value, direction, status,
    approved_by, is_done, last_modification, remaining_portions,
    after_hours, all_or_none, margin, account_id, exchange_closed,
    reserved_limit_exposure
)
SELECT 1, 3, 'MARKET', 5, 1, 180.50, NULL, NULL, 'SELL', 'CANCELLED',
       NULL, false, NOW()-INTERVAL '5 days', 5,
       false, false, false, 18, false, 0
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE user_id=1 AND listing_id=3 AND status='CANCELLED');

INSERT INTO orders (
    user_id, listing_id, order_type, quantity, contract_size,
    price_per_unit, limit_value, stop_value, direction, status,
    approved_by, is_done, last_modification, remaining_portions,
    after_hours, all_or_none, margin, account_id, exchange_closed,
    reserved_limit_exposure
)
SELECT 1, 5, 'LIMIT', 3, 1, 245.00, 245.00, NULL, 'BUY', 'PENDING',
       NULL, false, NOW()-INTERVAL '1 day', 3,
       false, false, false, 18, false, 0
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE user_id=1 AND listing_id=5 AND status='PENDING');

-- Futures + Forex orders for user 1 / account 18 (so "Moji nalozi" shows non-stock orders)
-- NOTE: created_at/executed_at are intentionally NOT seeded here. Those columns are
-- added later by migration 004_order_timestamps.sql, which backfills created_at from
-- last_modification and executed_at from last_modification for DONE orders. Seeding them
-- here would reference columns that do not exist yet at migration 002.
INSERT INTO orders (user_id,account_id,listing_id,order_type,direction,status,quantity,contract_size,price_per_unit,remaining_portions,is_done,all_or_none,margin,after_hours,last_modification)
SELECT v.* FROM (VALUES
 (1,18,101,'MARKET','BUY','DONE',100,1,117.20,0,true,false,false,false, now()),
 (1,18,102,'LIMIT','SELL','DONE',50,1,108.50,0,true,false,false,false, now()),
 (1,18,103,'MARKET','BUY','DONE',200,1,1.08,0,true,false,false,false, now()),
 (1,18,201,'MARKET','BUY','DONE',2,5000,620.00,0,true,false,false,false, now()),
 (1,18,202,'LIMIT','BUY','APPROVED',1,1000,78.50,1,false,false,false,false, now())
) AS v(user_id,account_id,listing_id,order_type,direction,status,quantity,contract_size,price_per_unit,remaining_portions,is_done,all_or_none,margin,after_hours,last_modification)
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id=v.user_id AND o.listing_id=v.listing_id AND o.order_type=v.order_type);
