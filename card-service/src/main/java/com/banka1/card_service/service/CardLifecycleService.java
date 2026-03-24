package com.banka1.card_service.service;

import com.banka1.card_service.dto.CardDetailDTO;
import com.banka1.card_service.dto.CardSummaryDTO;

import java.math.BigDecimal;
import java.util.List;

/**
 * Manages the lifecycle of existing cards — status transitions, limit changes, and retrieval.
 * Card creation is handled separately by {@link CardCreationService}.
 */
public interface CardLifecycleService {

    /**
     * Returns full details for a single card identified by its card number.
     *
     * @param cardNumber card number to look up
     * @return full card details
     */
    CardDetailDTO getCardByCardNumber(String cardNumber);

    /**
     * Returns the client ID of the owner of the given card.
     * Used to verify ownership before performing client-initiated operations.
     *
     * @param cardNumber card number to look up
     * @return client ID of the card owner
     */
    Long getClientIdByCardNumber(String cardNumber);

    /**
     * Returns all cards owned by the given client.
     * Card numbers in the result are masked.
     *
     * @param clientId client ID to filter by
     * @return list of masked card summaries (may be empty)
     */
    List<CardSummaryDTO> getCardsForClient(Long clientId);

    /**
     * Returns all cards linked to the given bank account number.
     * Card numbers in the result are masked.
     *
     * @param accountNumber account number to filter by
     * @return list of masked card summaries (may be empty)
     */
    List<CardSummaryDTO> getCardsByAccountNumber(String accountNumber);

    /**
     * Blocks an active card. Both clients and employees may call this.
     * Allowed transition: ACTIVE → BLOCKED.
     *
     * @param cardNumber card number to block
     */
    void blockCard(String cardNumber);

    /**
     * Unblocks a blocked card. Only employees may call this.
     * Allowed transition: BLOCKED → ACTIVE.
     *
     * @param cardNumber card number to unblock
     */
    void unblockCard(String cardNumber);

    /**
     * Permanently deactivates a card. Only employees may call this.
     * Allowed transition: ACTIVE → DEACTIVATED or BLOCKED → DEACTIVATED.
     * Deactivation is irreversible — a deactivated card cannot be reactivated.
     *
     * @param cardNumber card number to deactivate
     */
    void deactivateCard(String cardNumber);

    /**
     * Updates the spending limit on an existing card.
     *
     * @param cardNumber card number to update
     * @param newLimit new limit value, must be zero or greater
     */
    void updateCardLimit(String cardNumber, BigDecimal newLimit);
}
