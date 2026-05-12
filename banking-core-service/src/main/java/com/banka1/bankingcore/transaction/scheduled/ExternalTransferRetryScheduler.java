package com.banka1.bankingcore.transaction.scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * BV-7 retry scheduler (PR_05 C5.7 stub → PR_11 C11.15 real implementacija).
 *
 * <p>Spec (Celina 2): externe transfere stare > 72h treba retry-ovati ili eskalirati.
 *
 * <p>Implementacija:
 * <ol>
 *   <li>SELECT external_transfers WHERE status='PENDING' AND created_at < now() - 72h.
 *   <li>Eskalacija: ako retry_count >= 3, status='ESCALATED' + publish event za oncall.
 *   <li>Retry: ako retry_count < 3, increment + status='RETRYING' + publish event za clearing-house re-issue.
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalTransferRetryScheduler {

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;

    @Value("${transfers.retry.exchange:transfers.events}")
    private String exchange;

    @Value("${transfers.retry.max-attempts:3}")
    private int maxAttempts;

    @Scheduled(cron = "0 0 2,8,14,20 * * *")
    @SchedulerLock(name = "ExternalTransferRetry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    @Transactional
    public void retryFailedExternalTransfers() {
        log.info("BV-7 retry cron pokrenut.");

        // 1. Eskalacija (vec retry-ovani max puta).
        List<Map<String, Object>> escalations = jdbcTemplate.queryForList(
                "SELECT id, retry_count, amount, recipient_account, currency " +
                        "  FROM external_transfers " +
                        " WHERE status = 'PENDING' AND retry_count >= ? " +
                        "   AND created_at < now() - INTERVAL '72 hours'",
                maxAttempts);

        for (Map<String, Object> row : escalations) {
            Long id = ((Number) row.get("id")).longValue();
            jdbcTemplate.update(
                    "UPDATE external_transfers SET status = 'ESCALATED', updated_at = now() WHERE id = ?",
                    id);
            rabbitTemplate.convertAndSend(exchange, "transfer.escalated", Map.of(
                    "transferId", id,
                    "retryCount", row.get("retry_count"),
                    "amount", row.get("amount"),
                    "recipientAccount", row.get("recipient_account"),
                    "currency", row.get("currency"),
                    "alertOncall", true
            ));
            log.warn("BV-7 ESCALATED external transfer id={} (vise od {} retry-a)", id, maxAttempts);
        }

        // 2. Retry (stari < 72h sa retry_count < max).
        List<Map<String, Object>> retries = jdbcTemplate.queryForList(
                "SELECT id, retry_count, amount, recipient_account, currency " +
                        "  FROM external_transfers " +
                        " WHERE status = 'PENDING' AND retry_count < ? " +
                        "   AND created_at < now() - INTERVAL '72 hours'",
                maxAttempts);

        for (Map<String, Object> row : retries) {
            Long id = ((Number) row.get("id")).longValue();
            int currentRetry = ((Number) row.get("retry_count")).intValue();
            jdbcTemplate.update(
                    "UPDATE external_transfers SET retry_count = retry_count + 1, status = 'RETRYING', updated_at = now() WHERE id = ?",
                    id);
            rabbitTemplate.convertAndSend(exchange, "transfer.retry", Map.of(
                    "transferId", id,
                    "retryAttempt", currentRetry + 1,
                    "amount", row.get("amount"),
                    "recipientAccount", row.get("recipient_account"),
                    "currency", row.get("currency")
            ));
            log.info("BV-7 RETRY external transfer id={} attempt={}", id, currentRetry + 1);
        }

        log.info("BV-7 retry zavrsen: escalated={} retried={}", escalations.size(), retries.size());
    }
}
