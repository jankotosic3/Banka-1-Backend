package com.banka1.card_service.dto;

/**
 * Pair containing the one-time plain CVV and its hashed representation.
 * This DTO is internal to the card-creation flow and exists to make it explicit
 * that two different values are produced:
 *  - one for immediate display and
 *  - one for persistence.
 *
 * @param plainCvv one-time plain CVV shown to the caller
 * @param hashedCvv hashed CVV stored in the database
 */
public record GeneratedCvv(
        String plainCvv,
        String hashedCvv
) {
}
