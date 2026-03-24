package com.banka1.card_service.dto;

import com.banka1.card_service.domain.Card;
import lombok.Getter;

/**
 * Compact card representation used in list responses.
 * The card number is masked to protect sensitive data — only the first four
 * and last four digits are visible, with asterisks replacing the middle digits.
 *
 * Example:
 * a card number {@code 5798123456785571} is returned as {@code 5798********5571}.
 *
 * The CVV is never included in any list or detail response.
 */
@Getter
public class CardSummaryDTO {

    /**
     * Masked card number safe for display in lists.
     * Format: first 4 digits + asterisks + last 4 digits.
     */
    private final String maskedCardNumber;

    private final String accountNumber;

    public CardSummaryDTO(Card card) {
        this.maskedCardNumber = maskCardNumber(card.getCardNumber());
        this.accountNumber = card.getAccountNumber();
    }

    private static String maskCardNumber(String cardNumber) {
        int len = cardNumber.length();
        return cardNumber.substring(0, 4) + "*".repeat(len - 8) + cardNumber.substring(len - 4);
    }
}
