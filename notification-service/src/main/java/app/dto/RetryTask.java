package app.dto;

import java.time.Instant;

/**
 * Minimal in-memory scheduling metadata for a retryable delivery.
 *
 * @param deliveryId    internal delivery identifier stored in PostgreSQL
 * @param nextAttemptAt timestamp when the next retry should be attempted
 */
public record RetryTask(
        String deliveryId,
        Instant nextAttemptAt
) { }
