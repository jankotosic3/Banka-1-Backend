-- Fix bank internal RSD checking account: DOO (BUSINESS) requires a company but bank account has none.
-- Change to STANDARDNI (PERSONAL) which does not require a company.
UPDATE account_table
SET account_concrete = 'STANDARDNI'
WHERE vlasnik = -1
  AND account_type = 'CHECKING'
  AND account_concrete = 'DOO';

-- Update all 18-digit account numbers to 19 digits by inserting an extra '0' after position 7.
UPDATE account_table
SET broj_racuna = SUBSTRING(broj_racuna, 1, 7) || '0' || SUBSTRING(broj_racuna, 8)
WHERE LENGTH(broj_racuna) = 18;
