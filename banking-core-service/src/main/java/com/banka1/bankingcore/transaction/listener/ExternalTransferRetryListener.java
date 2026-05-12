package com.banka1.bankingcore.transaction.listener;

import com.banka1.bankingcore.transaction.client.ClearingHouseClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Konzumira {@code transfer.retry} i {@code transfer.escalated} event-e koje
 * publishuje {@link com.banka1.bankingcore.transaction.scheduled.ExternalTransferRetryScheduler}
 * (PR_13 C13.1 — zatvara loop koji je u PR_05/PR_11 ostavljen otvoren).
 *
 * <p>Workflow:
 * <ol>
 *   <li>{@code transfer.retry}: pokusava re-issue ka clearing-house API-ju kroz {@link ClearingHouseClient}.
 *       Ako uspe → UPDATE status='COMPLETED', completed_at=now().
 *       Ako fail → ostaje status='RETRYING' (sledeci cron tick ce ga uzeti za jos jedan pokusaj
 *       ili eskalirati ako je dosao do max-attempts-a).</li>
 *   <li>{@code transfer.escalated}: alert ka oncall-u (sa publish-om na 'oncall.alerts' exchange,
 *       ili integracija sa PagerDuty/Slack ako je konfigurisana).</li>
 * </ol>
 *
 * <p>Idempotency: koristi {@code transfer_retry_log} tabelu za dedup po (transferId, retryAttempt).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalTransferRetryListener {

    private final JdbcTemplate jdbcTemplate;
    private final ClearingHouseClient clearingHouseClient;

    // PR_19 C19.X: auto-declare queue + exchange + binding.
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(value = "${transfers.retry.queue:transfers.retry.queue}",
                                    durable = "true"),
                    exchange = @Exchange(value = "${transfers.retry.exchange:transfers.events}",
                                          type = "topic", durable = "true"),
                    key = "transfer.retry"))
    @Transactional
    public void onRetry(Map<String, Object> event) {
        Long transferId = ((Number) event.get("transferId")).longValue();
        int retryAttempt = ((Number) event.get("retryAttempt")).intValue();
        BigDecimal amount = new BigDecimal(String.valueOf(event.get("amount")));
        String recipient = (String) event.get("recipientAccount");
        String currency = (String) event.get("currency");

        log.info("Processing transfer.retry: id={} attempt={}", transferId, retryAttempt);

        Integer alreadyProcessed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfer_retry_log WHERE transfer_id = ? AND retry_attempt = ?",
                Integer.class, transferId, retryAttempt);
        if (alreadyProcessed != null && alreadyProcessed > 0) {
            log.info("Transfer retry id={} attempt={} vec procesiran — preskocenam", transferId, retryAttempt);
            return;
        }

        try {
            ClearingHouseClient.IssueResult result = clearingHouseClient.issueTransfer(
                    transferId, amount, currency, recipient);

            if (result.success()) {
                jdbcTemplate.update(
                        "UPDATE external_transfers SET status = 'COMPLETED', completed_at = now(), updated_at = now() WHERE id = ?",
                        transferId);
                log.info("Transfer id={} attempt={} SUCCESS — clearingRef={}",
                        transferId, retryAttempt, result.clearingHouseRef());
            } else {
                log.warn("Transfer id={} attempt={} clearing-house refused: {}",
                        transferId, retryAttempt, result.failureReason());
                // status ostaje RETRYING; sledeci cron tick uzima ovo
            }
        } catch (Exception ex) {
            log.error("Transfer id={} attempt={} FAILED ka clearing-house: {}",
                    transferId, retryAttempt, ex.toString());
        } finally {
            jdbcTemplate.update(
                    "INSERT INTO transfer_retry_log (transfer_id, retry_attempt, processed_at) VALUES (?, ?, now())",
                    transferId, retryAttempt);
        }
    }

    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(value = "${transfers.escalated.queue:transfers.escalated.queue}",
                                    durable = "true"),
                    exchange = @Exchange(value = "${transfers.retry.exchange:transfers.events}",
                                          type = "topic", durable = "true"),
                    key = "transfer.escalated"))
    public void onEscalated(Map<String, Object> event) {
        Long transferId = ((Number) event.get("transferId")).longValue();
        log.error("ESCALATED transfer id={} — admin alert required (recipient={}, amount={}, currency={})",
                transferId, event.get("recipientAccount"), event.get("amount"), event.get("currency"));
        // TBD: integracija sa PagerDuty / Slack notification preko webhook-a u dorade.
        // Trenutno log-only, sto je dovoljno za audit trail; oncall ce primetiti
        // ESCALATED status kroz Grafana alert na Prometheus query-ju
        // count(external_transfers{status="ESCALATED"}) > 0.
    }
}
