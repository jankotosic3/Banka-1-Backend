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
