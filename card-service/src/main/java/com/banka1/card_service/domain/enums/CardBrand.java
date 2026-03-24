package com.banka1.card_service.domain.enums;

import lombok.Getter;

/**
 * Supported card brands together with their display names and card-number lengths.
 * Each enum constant represents one issuer-format profile used during generation and validation.
 * Visa uses prefix 4 and length 16.
 * MasterCard uses prefixes 51-55 or 2221-2720 and length 16.
 * DinaCard uses prefix 9891 and length 16.
 * AmEx uses prefixes 34 or 37 and length 15.
 *
 * Important to notice here, CardBrand is an Enum, but it is not just a default list of values,
 * it is a centralized place where we grouped the rules for every card brand:
 *  - how it should be called
 *  - how many digits a card should have
 *  - and a prefix from which we know whether the number is visa/mastercard/...
 */
@Getter
public enum CardBrand {

    /**
     * Visa card profile.
     */
    VISA("Visa", 16),

    /**
     * MasterCard card profile.
     */
    MASTERCARD("MasterCard", 16),

    /**
     * DinaCard card profile.
     */
    DINACARD("DinaCard", 16),

    /**
     * American Express card profile.
     */
    AMEX("AmEx", 15);

    /**
     * Human-readable brand name (i.e. "Visa")
     * It is used in composed card names such as {@code "Visa Debit"}.
     */
    private final String displayName;

    /**
     * Total number of digits in the final card number, including the Luhn check digit.
     */
    private final int cardNumberLength;

    CardBrand(String displayName, int cardNumberLength) {
        this.displayName = displayName;
        this.cardNumberLength = cardNumberLength;
    }

    /**
     * Builds the persisted card name from the brand and current card type.
     * Example:
     * {@link #VISA} becomes {@code "Visa Debit"}.
     *
     * @return human-readable card name
     */
    public String toCardName() {
        return displayName + " Debit";
    }

    /**
     * Checks whether the provided card number matches this brand's issuer format.
     * This method validates brand-specific prefix and length only.
     * Luhn checksum validation is intentionally handled separately by the card-number generator service.
     *
     * @param cardNumber full card number
     * @return {@code true} when the prefix and length match the brand
     */
    public boolean matches(String cardNumber) {
        if (cardNumber == null || !cardNumber.chars().allMatch(Character::isDigit)
                || cardNumber.length() != cardNumberLength) {
            return false;
        }

        return switch (this) {
            case VISA -> cardNumber.startsWith("4");
            case DINACARD -> cardNumber.startsWith("9891");
            case AMEX -> cardNumber.startsWith("34") || cardNumber.startsWith("37");
            case MASTERCARD -> isMastercardNumber(cardNumber);
        };
    }

    /**
     * Checks whether the provided number uses one of the supported MasterCard issuer ranges.
     *
     * @param cardNumber full candidate card number
     * @return {@code true} when the number starts with a valid MasterCard prefix
     */
    private boolean isMastercardNumber(String cardNumber) {
        int firstTwoDigits = Integer.parseInt(cardNumber.substring(0, 2));
        if (firstTwoDigits >= 51 && firstTwoDigits <= 55) {
            return true;
        }

        int firstFourDigits = Integer.parseInt(cardNumber.substring(0, 4));
        return firstFourDigits >= 2221 && firstFourDigits <= 2720;
    }
}
