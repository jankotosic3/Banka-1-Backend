--liquibase formatted sql

-- changeset jovan:9
-- PR_07 C7.2: kaskadni soft-delete kroz database trigger.
--
-- Pre PR_07: kada je klijent obrisan (clients.deleted = true), njegovi racuni,
-- kartice, transferi su ostajali "vidljivi" jer @SQLRestriction proverava samo
-- account.deleted, ne i account.client.deleted. Posledica: soft-deleted klijent
-- bi ipak mogao da se prikaze u employee-side reportu.
--
-- Posle PR_07: AFTER UPDATE trigger na clients tabeli automatski markira
-- account-e kao deleted kada je client.deleted promenjen sa false na true.
-- Trigger se primenjuje kaskadno na cards, transactions, transfers, otps —
-- sve preko isti pattern.

-- Sa user-service migracije, ne ovde — ali banking-core ima pristup tabelama
-- koje su u istom postgres clusteru kao user-service.user_service DB.
-- (PostgreSQL trigger preko cross-database link-a je kompleksno; alternativno
-- resenje: app-level cascade kroz @PostUpdate listener na Klijent entitet-u
-- u user-service-u, koji publishuje 'client.soft-deleted' event a banking-core
-- ga konzumira i bulk-update racune.)

-- Implementacija opcija A (preferred): app-level event listener.
-- Liquibase changeset samo dodaje audit kolonu da se zna kada je kaskada izvrsena.

ALTER TABLE accounts ADD COLUMN IF NOT EXISTS deleted_due_to_client_id BIGINT;
ALTER TABLE cards    ADD COLUMN IF NOT EXISTS deleted_due_to_client_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_accounts_deleted_due_to_client_id ON accounts(deleted_due_to_client_id);
CREATE INDEX IF NOT EXISTS idx_cards_deleted_due_to_client_id    ON cards(deleted_due_to_client_id);

-- rollback DROP INDEX IF EXISTS idx_accounts_deleted_due_to_client_id;
-- rollback DROP INDEX IF EXISTS idx_cards_deleted_due_to_client_id;
-- rollback ALTER TABLE accounts DROP COLUMN IF EXISTS deleted_due_to_client_id;
-- rollback ALTER TABLE cards    DROP COLUMN IF EXISTS deleted_due_to_client_id;
