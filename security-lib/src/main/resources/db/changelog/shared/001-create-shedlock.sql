--liquibase formatted sql

-- changeset jovan:1 context:always
-- PR_06 C6.1: ShedLock distribuirani lock-ovi za @Scheduled metode.
-- Tabela se kreira u svakoj DB-u koja ima neki @SchedulerLock servis (banking-core,
-- trading-service, market-service, user-service). Svaki Liquibase master changelog
-- u tim modulima ce uci ovaj fajl preko relativnog include-a.

CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)    NOT NULL,
    lock_until  TIMESTAMP      NOT NULL,
    locked_at   TIMESTAMP      NOT NULL,
    locked_by   VARCHAR(255)   NOT NULL,
    PRIMARY KEY (name)
);

-- rollback DROP TABLE IF EXISTS shedlock;
