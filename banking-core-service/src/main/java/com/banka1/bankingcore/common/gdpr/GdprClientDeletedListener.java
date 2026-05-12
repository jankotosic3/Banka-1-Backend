package com.banka1.bankingcore.common.gdpr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Konzumira {@code gdpr.client.soft-deleted} event i bulk-update-uje sve account-e
 * i cards povezane sa obrisanim klijentom (PR_11 C11.13).
 *
 * <p>Idempotentnost: koristi {@code WHERE deleted = false} guard tako da
 * ponovljeni redelivery ne menja stanje. Atomic SQL update — bez @Transactional
 * race-a sa drugim klijent operacijama.
 *
 * <p>Failure handling: ako bulk update fail-uje, RabbitMQ ce poruku redelivery-jati
 * po default DLX policy-ju (PR_06 RabbitConfig). Posle 3 fail-ova porука ide u DLQ
 * gde admin moze rucno da je ponovi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GdprClientDeletedListener {

    private final JdbcTemplate jdbcTemplate;

    // PR_19 C19.X: auto-declare queue + binding tako da banking-core ne pokusava
    // passive-declare na queue koja jos uvek ne postoji u RabbitMQ-u (i koja
    // ce ionako biti kreirana tek kada listener startuje).
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(value = "${gdpr.banking-core.queue:gdpr.banking-core.client-deleted}",
                                    durable = "true"),
                    exchange = @Exchange(value = "${gdpr.exchange:gdpr.events}",
                                          type = "topic", durable = "true"),
                    key = "gdpr.client.soft-deleted"))
    @Transactional
    public void onClientSoftDeleted(Map<String, Object> event) {
        Long clientId = ((Number) event.get("clientId")).longValue();
        String eventId = (String) event.get("eventId");
        log.info("GDPR cascade: clientId={} event={}", clientId, eventId);

        // Idempotentnost preko gdpr_event_log tabele (kreirana u PR_11 C11.13 migraciji).
        Integer alreadyProcessed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gdpr_event_log WHERE event_id = ? AND listener = 'banking-core'",
                Integer.class, eventId);
        if (alreadyProcessed != null && alreadyProcessed > 0) {
            log.info("GDPR cascade: event {} vec procesiran — preskocenam", eventId);
            return;
        }

        int accountsUpdated = jdbcTemplate.update(
                "UPDATE accounts " +
                        "   SET deleted = true, deleted_due_to_client_id = ?, updated_at = now() " +
                        " WHERE owner_id = ? AND deleted = false",
                clientId, clientId);

        int cardsUpdated = jdbcTemplate.update(
                "UPDATE cards " +
                        "   SET deleted = true, deleted_due_to_client_id = ?, updated_at = now() " +
                        " WHERE account_id IN (SELECT id FROM accounts WHERE owner_id = ?) " +
                        "   AND deleted = false",
                clientId, clientId);

        // OTPs i transferi se ne obrisu kaskadno — drze se 30 dana radi audita pre brisanja.

        jdbcTemplate.update(
                "INSERT INTO gdpr_event_log (event_id, listener, processed_at, summary) VALUES (?, 'banking-core', now(), ?)",
                eventId, "accounts=" + accountsUpdated + ", cards=" + cardsUpdated);

        log.info("GDPR cascade clientId={}: deleted accounts={} cards={}", clientId, accountsUpdated, cardsUpdated);
    }
}
