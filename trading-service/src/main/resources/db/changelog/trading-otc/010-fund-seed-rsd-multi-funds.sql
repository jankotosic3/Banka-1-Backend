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
