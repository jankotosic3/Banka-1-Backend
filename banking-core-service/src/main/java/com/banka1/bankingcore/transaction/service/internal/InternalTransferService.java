package com.banka1.bankingcore.transaction.service.internal;

import com.banka1.bankingcore.account.client.AccountServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Banking-core servis za interne transfere koje SAGA orchestrator inicira
 * (PR_14 C14.6).
 *
 * <p>Sluzbenik za debit/credit je account-service ({@link AccountServiceClient});
 * banking-core samo posreduje i pamti audit-log u {@code internal_transfer_log}
 * tabeli. To omogucava idempotentni reverseTransfer (kompenzacija) — ako
 * orchestrator reverz pozove vise puta, drugi i sledeci pozivi vide vec
 * REVERSED status i vracaju OK bez ponovnog credit/debit-a.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalTransferService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<AccountServiceClient> accountServiceClientProvider;

    @Transactional
    public Transfer transfer(String fromAccount, String toAccount, BigDecimal amount, String correlationId) {
        AccountServiceClient client = clientOrThrow();
        AccountServiceClient.AccountDetails source = client.getByNumber(fromAccount);
        AccountServiceClient.AccountDetails target = client.getByNumber(toAccount);

        client.debit(fromAccount, amount, source.ownerId());
        client.credit(toAccount, amount, target.ownerId());

        UUID transferId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO internal_transfer_log (transfer_id, correlation_id, from_account, to_account, amount, currency, status) "
                        + "VALUES (?::uuid, ?, ?, ?, ?, COALESCE(?, 'RSD'), 'COMPLETED')",
                transferId.toString(), correlationId, fromAccount, toAccount, amount, source.currency());

        log.info("Internal transfer OK: from={} to={} amount={} transferId={} correlationId={}",
                fromAccount, toAccount, amount, transferId, correlationId);
        return new Transfer(transferId.toString(), "COMPLETED");
    }

    @Transactional
    public Transfer reverse(String transferId, String correlationId) {
        AccountServiceClient client = clientOrThrow();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT from_account, to_account, amount, status FROM internal_transfer_log "
                        + "WHERE transfer_id = ?::uuid",
                transferId);
        if (rows.isEmpty()) {
            log.warn("Reverse transfer: transfer_id={} ne postoji — duplicate kompenzacija? correlationId={}",
                    transferId, correlationId);
            return new Transfer(transferId, "UNKNOWN");
        }
        Map<String, Object> row = rows.get(0);
        String currentStatus = (String) row.get("status");
        if (!"COMPLETED".equals(currentStatus)) {
            log.info("Reverse transfer: transfer_id={} vec u state-u {} — no-op. correlationId={}",
                    transferId, currentStatus, correlationId);
            return new Transfer(transferId, currentStatus);
        }

        String fromAccount = (String) row.get("from_account");
        String toAccount = (String) row.get("to_account");
        BigDecimal amount = (BigDecimal) row.get("amount");

        // Reverz: vraca novac sa toAccount na fromAccount. Vlasnici se ponovo cita
        // jer u medjuvremenu @SQLRestriction-u soft-deleted klijent moze biti.
        AccountServiceClient.AccountDetails source = client.getByNumber(toAccount);
        AccountServiceClient.AccountDetails target = client.getByNumber(fromAccount);
        client.debit(toAccount, amount, source.ownerId());
        client.credit(fromAccount, amount, target.ownerId());

        jdbcTemplate.update(
                "UPDATE internal_transfer_log SET status='REVERSED', reversed_at=NOW() "
                        + "WHERE transfer_id = ?::uuid AND status='COMPLETED'",
                transferId);

        log.info("Reverse transfer OK: transfer_id={} from->to swap amount={} correlationId={}",
                transferId, amount, correlationId);
        return new Transfer(transferId, "REVERSED");
    }

    private AccountServiceClient clientOrThrow() {
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("AccountServiceClient nije dostupan.");
        }
        return client;
    }

    public record Transfer(String transferId, String status) {}
}
