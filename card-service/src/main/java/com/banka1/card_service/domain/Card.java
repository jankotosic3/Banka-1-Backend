package com.banka1.card_service.domain;

import com.banka1.card_service.domain.enums.CardStatus;
import com.banka1.card_service.domain.enums.CardType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Persistent card aggregate stored by the card service.
 * This entity represents the business state of one debit card linked to one bank account.
 * It stores the generated card number, derived product name, validity window, CVV hash,
 * spending limit, and lifecycle status.
 * Example persisted values:
 * {@code cardNumber = "4111111111111111"},
 * {@code cardName = "Visa Debit"},
 * {@code status = ACTIVE},
 * {@code cvv = "$argon2id$v=19$..."}.
 * Plain CVV is intentionally never stored in this entity.
 */
@Entity
@Table(
        name = "cards",
        indexes = {
                @Index(name = "idx_cards_account_number", columnList = "account_number"),
                @Index(name = "idx_cards_client_id", columnList = "client_id"),
                @Index(name = "idx_cards_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class Card extends BaseEntity {

    /**
     * Full card number including the final Luhn check digit.
     * Supported lengths are 16 digits for Visa, MasterCard, and DinaCard, and 15 digits for AmEx.
     */
    @Column(name = "card_number", nullable = false, unique = true, length = 16)
    private String cardNumber;

    /**
     * Product type of the card.
     * The current business scope supports only {@link CardType#DEBIT}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "card_type", nullable = false, length = 20)
    private CardType cardType = CardType.DEBIT;

    /**
     * Human-readable product name shown to callers and downstream services.
     * Example: {@code "Visa Debit"}.
     */
    @Column(name = "card_name", nullable = false, length = 50)
    private String cardName;

    /**
     * Business creation date of the card.
     * This is the date used to derive expiration, not the technical database insert timestamp.
     */
    @Column(name = "creation_date", nullable = false)
    private LocalDate creationDate;

    /**
     * Expiration date of the card.
     * The creation flow sets it automatically to five years after {@link #creationDate}.
     */
    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    /**
     * Linked bank account number that owns the card.
     * The value is modeled as a string because it is an identifier, not a numeric value for calculations.
     */
    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    /**
     * ID of the client who owns this card.
     * Denormalized from the account to avoid cross-service calls when
     * listing a client's cards or verifying card ownership.
     */
    @Column(name = "client_id", nullable = false)
    private Long clientId;

    /**
     * Hashed CVV value.
     * The service stores only the hash, while the plain three-digit CVV is returned once during creation.
     */
    @Column(name = "cvv", nullable = false, length = 255)
    private String cvv;

    /**
     * Spending limit assigned to the card.
     * {@link BigDecimal} is used so monetary precision is preserved.
     */
    @Column(name = "card_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal cardLimit;

    /**
     * Current lifecycle state of the card.
     * New cards start in {@link CardStatus#ACTIVE}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CardStatus status = CardStatus.ACTIVE;
}
