
#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE banka;
    CREATE DATABASE notification_db;
    CREATE DATABASE clientdb;
    CREATE DATABASE accountdb;
    CREATE DATABASE card_db;
    CREATE DATABASE transferdb;
    CREATE DATABASE exchange_db;
    CREATE DATABASE verificationdb;
    CREATE DATABASE transaction_db;
    CREATE DATABASE orderdb;
    CREATE DATABASE stock_db;
    CREATE DATABASE credit_db;
    CREATE DATABASE saga_db;
    -- PR_19 C19.X: novi konsolidovani DB-ovi (user/banking-core/market/trading).
    CREATE DATABASE user_service;
    CREATE DATABASE banking_core;
    CREATE DATABASE market_service;
    CREATE DATABASE trading;
    -- PR_32: interbank-service (X-Api-Key auth, inter-bank routing)
    CREATE DATABASE interbank_service;
EOSQL
