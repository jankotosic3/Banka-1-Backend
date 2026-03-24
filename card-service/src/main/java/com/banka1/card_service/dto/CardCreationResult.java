package com.banka1.card_service.dto;

import com.banka1.card_service.domain.Card;

/**
 * Result returned after a new card is created.
 * This DTO intentionally contains both the persisted {@link Card} entity
 * and the ONE-TIME plain CVV that can be shown to the caller immediately after creation.
 *
 * Example:
 * the caller may receive {@code plainCvv = "123"} once,
 * while {@code card.getCvv()} contains only the hash.
 *
 * @param card persisted card entity
 * @param plainCvv one-time plain CVV value
 */
public record CardCreationResult(
        Card card,
        String plainCvv
) {
}
