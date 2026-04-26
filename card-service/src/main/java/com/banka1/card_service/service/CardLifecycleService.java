package com.banka1.card_service.service;

import com.banka1.card_service.dto.card_management.response.CardDetailDTO;
import com.banka1.card_service.dto.card_management.response.CardInternalSummaryDTO;
import com.banka1.card_service.dto.card_management.response.CardSummaryDTO;

import java.math.BigDecimal;
import java.util.List;

/**
 * Manages the lifecycle of existing cards - status transitions, limit changes, and retrieval.
 * Card creation is handled separately by {@link CardCreationService}.
 */
public interface CardLifecycleService {

    /**
     * Returns full details for a single card identified by its database ID.
     *
     * @param cardId card ID to look up
     * @return full card details
     */
    CardDetailDTO getCardById(Long cardId);

    /**
     * Returns the client ID of the owner of the given card.
     * Used to verify ownership before performing client-initiated operations.
     *
     * @param cardId card ID to look up
     * @return client ID of the card owner
     */
    Long getClientIdByCardId(Long cardId);

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
     * Returns all cards linked to the given account number in the richer internal shape
     * consumed by trusted service-to-service callers such as account-service when it
     * populates account detail responses.
     *
     * @param accountNumber account number to filter by
     * @return list of internal card summaries (may be empty)
     */
    List<CardInternalSummaryDTO> getInternalCardsByAccountNumber(String accountNumber);

    /**
     * Blocks an active card identified by database ID.
     * Both clients and employees may call this.
     * Allowed transition: ACTIVE -> BLOCKED.
     *
     * @param cardId card ID to block
     */
    void blockCard(Long cardId);

    /**
     * Unblocks a blocked card. Only employees may call this.
     * Allowed transition: BLOCKED -> ACTIVE.
     *
     * @param cardId card ID to unblock
     */
    void unblockCard(Long cardId);

    /**
     * Permanently deactivates a card. Only employees may call this.
     * Allowed transition: ACTIVE -> DEACTIVATED or BLOCKED -> DEACTIVATED.
     * Deactivation is irreversible - a deactivated card cannot be reactivated.
     *
     * @param cardId card ID to deactivate
     */
    void deactivateCard(Long cardId);

    /**
     * Updates the spending limit on an existing card identified by database ID.
     *
     * @param cardId card ID to update
     * @param newLimit new limit value, must be zero or greater
     */
    void updateCardLimit(Long cardId, BigDecimal newLimit);
}
