package com.banka1.card_service.dto.card_management.response;

import com.banka1.card_service.domain.Card;
import com.banka1.card_service.domain.enums.CardStatus;
import com.banka1.card_service.util.SensitiveDataMasker;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Richer card summary used by the employee "Portal za upravljanje karticama" (PR_32).
 *
 * <p>Returned by {@code GET /api/cards/all} together with paging metadata so the
 * bank-wide admin portal can render a status badge, brand label, limit value, and
 * the owning client/account in one table without round-tripping per row.
 *
 * <p>The card number remains masked using the standard
 * {@code 5798********5571} format defined by {@link SensitiveDataMasker}.
 * The CVV hash is intentionally excluded from this DTO and from every other
 * card-service response DTO besides the one-time creation payload.
 */
@Getter
public class CardAdminSummaryDTO {

    /**
     * Stable card identifier used for follow-up calls
     * (block / unblock / deactivate / get details).
     */
    private final Long id;

    /**
     * Masked card number (4 leading digits + 8 asterisks + 4 trailing digits).
     */
    private final String cardNumber;

    /**
     * Human-readable card brand label, e.g. "Visa Debit".
     */
    private final String brand;

    /**
     * Current lifecycle state (ACTIVE / BLOCKED / DEACTIVATED).
     */
    private final CardStatus status;

    /**
     * Linked account number that owns the card.
     */
    private final String accountNumber;

    /**
     * Client identifier of the card owner.
     */
    private final Long clientId;

    /**
     * Spending limit assigned to the card.
     */
    private final BigDecimal cardLimit;

    public CardAdminSummaryDTO(Card card) {
        this.id = card.getId();
        this.cardNumber = SensitiveDataMasker.maskCardNumber(card.getCardNumber());
        this.brand = card.getCardName();
        this.status = card.getStatus();
        this.accountNumber = card.getAccountNumber();
        this.clientId = card.getClientId();
        this.cardLimit = card.getCardLimit();
    }
}
