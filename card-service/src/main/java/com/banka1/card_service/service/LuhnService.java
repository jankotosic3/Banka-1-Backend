package com.banka1.card_service.service;

import org.springframework.stereotype.Service;

/**
 * Service responsible for Luhn checksum operations.
 * The Luhn algorithm is used to generate the final check digit for newly created card numbers
 * and to validate that an existing card number has the correct checksum.
 *
 * Example:
 * payload {@code 411111111111111} produces check digit {@code 1},
 * resulting in the valid card number {@code 4111111111111111}.
 */
@Service
public class LuhnService {

    /**
     * Calculates the Luhn check digit for a numeric payload without the final check digit.
     *
     * @param numberWithoutCheckDigit numeric payload without the check digit
     * @return calculated check digit
     */
    public int calculateCheckDigit(String numberWithoutCheckDigit) {
        validateNumeric(numberWithoutCheckDigit);

        int sum = 0;
        boolean doubleDigit = true;
        for (int index = numberWithoutCheckDigit.length() - 1; index >= 0; index--) {
            int digit = numberWithoutCheckDigit.charAt(index) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }

        return (10 - (sum % 10)) % 10;
    }

    /**
     * Validates a full card number using the Luhn checksum.
     * This method returns {@code false} instead of throwing for invalid full values
     * because validation is commonly used in conditional checks and tests.
     *
     * @param cardNumber full card number including the check digit
     * @return {@code true} when the card number passes the Luhn check
     */
    public boolean isValid(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank() || !cardNumber.chars().allMatch(Character::isDigit)) {
            return false;
        }

        int sum = 0;
        boolean doubleDigit = false;
        for (int index = cardNumber.length() - 1; index >= 0; index--) {
            int digit = cardNumber.charAt(index) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }

        return sum % 10 == 0;
    }

    /**
     * Validates that the input is a non-empty numeric string before checksum generation.
     *
     * @param value card-number payload without the final check digit
     */
    private void validateNumeric(String value) {
        if (value == null || value.isBlank() || !value.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Card number payload must contain digits only.");
        }
    }
}
