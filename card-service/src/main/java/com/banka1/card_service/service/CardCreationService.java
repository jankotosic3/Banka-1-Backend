package com.banka1.card_service.service;

import com.banka1.card_service.domain.enums.CardBrand;
import com.banka1.card_service.dto.CardCreationResult;

import java.math.BigDecimal;

/**
 * Application-service contract responsible for creating new debit cards.
 * The implementation is expected to validate input, generate a unique brand-compliant card number,
 * generate and hash a CVV, set derived defaults such as card type and expiration date,
 * and persist the resulting entity.
 */
public interface CardCreationService {

    /**
     * Creates and persists a debit card with generated card number and CVV.
     * Example:
     * calling this method with account number {@code "265000000000001234"} and brand {@code VISA}
     * can return a saved card such as {@code "4123456789012349"} together with a one-time plain CVV.
     *
     * @param accountNumber linked bank account number
     * @param cardBrand requested card brand
     * @param cardLimit per-card spending limit
     * @return created card together with the one-time plain CVV
     */
    CardCreationResult createCard(String accountNumber, CardBrand cardBrand, BigDecimal cardLimit);
}
