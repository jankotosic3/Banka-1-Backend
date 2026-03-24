package com.banka1.card_service.service;

import com.banka1.card_service.domain.enums.CardBrand;
import com.banka1.card_service.exception.BusinessException;
import com.banka1.card_service.exception.ErrorCode;
import com.banka1.card_service.repository.CardRepository;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * Generates unique card numbers for all supported brands.
 * Generation follows two mandatory rules.
 * First, the produced number must respect the brand-specific issuer prefix and total length.
 * Second, the final digit must satisfy the Luhn checksum.
 *
 * Example formats:
 * Visa: {@code 4XXXXXXXXXXXXXXX}
 * MasterCard: {@code 51XXXXXXXXXXXXXX} or {@code 2221XXXXXXXXXXXX}
 * DinaCard: {@code 9891XXXXXXXXXXXX}
 * AmEx: {@code 34XXXXXXXXXXXXX} or {@code 37XXXXXXXXXXXXX}
 */
@Service
public class CardNumberGenerator {

    /**
     * Maximum number of retries before unique-number generation fails hard.
     * This prevents endless loops in case of repeated collisions.
     */
    private static final int MAX_GENERATION_ATTEMPTS = 20;

    /**
     * Repository used to verify card-number uniqueness before returning the generated value.
     */
    private final CardRepository cardRepository;

    /**
     * Helper used to calculate and verify the final Luhn digit.
     */
    private final LuhnService luhnService;

    /**
     * Cryptographically strong random generator used for issuer-prefix selection and payload digits.
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Creates the generator with the collaborators required for checksum calculation and uniqueness checks.
     *
     * @param cardRepository repository used for uniqueness checks
     * @param luhnService checksum helper used to calculate the final check digit
     */
    public CardNumberGenerator(CardRepository cardRepository, LuhnService luhnService) {
        this.cardRepository = cardRepository;
        this.luhnService = luhnService;
    }

    /**
     * Generates a unique card number for the requested brand.
     * The method chooses a valid brand prefix, generates the random middle section,
     * calculates the Luhn digit, and checks whether the resulting number is already persisted.
     *
     * @param cardBrand requested card brand
     * @return unique card number
     */
    public String generateCardNumber(CardBrand cardBrand) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String prefix = generatePrefix(cardBrand);
            int payloadLength = cardBrand.getCardNumberLength() - prefix.length() - 1;
            String payload = prefix + randomDigits(payloadLength);
            String cardNumber = payload + luhnService.calculateCheckDigit(payload);

            if (!cardRepository.existsByCardNumber(cardNumber)) {
                return cardNumber;
            }
        }

        throw new BusinessException(
                ErrorCode.CARD_NUMBER_GENERATION_FAILED,
                "Could not generate a unique card number after " + MAX_GENERATION_ATTEMPTS + " attempts."
        );
    }

    /**
     * Checks whether a card number matches the brand format and passes the Luhn checksum.
     * This convenience method is useful in unit tests and future validation flows
     * that need one combined brand-plus-checksum check.
     *
     * @param cardNumber card number to validate
     * @param cardBrand expected card brand
     * @return {@code true} when both brand format and checksum are valid
     */
    public boolean isValidForBrand(String cardNumber, CardBrand cardBrand) {
        return cardBrand.matches(cardNumber) && luhnService.isValid(cardNumber);
    }

    /**
     * Generates the issuer prefix for the requested brand.
     *
     * @param cardBrand requested card brand
     * @return valid brand-specific prefix
     */
    private String generatePrefix(CardBrand cardBrand) {
        return switch (cardBrand) {
            case VISA -> "4";
            case DINACARD -> "9891";
            case AMEX -> secureRandom.nextBoolean() ? "34" : "37";
            case MASTERCARD -> generateMastercardPrefix();
        };
    }

    /**
     * Generates one valid MasterCard issuer prefix.
     * Supported ranges are {@code 51-55} and {@code 2221-2720}.
     *
     * @return valid MasterCard prefix
     */
    private String generateMastercardPrefix() {
        if (secureRandom.nextBoolean()) {
            return String.valueOf(secureRandom.nextInt(51, 56));
        }
        return String.valueOf(secureRandom.nextInt(2221, 2721));
    }

    /**
     * Generates a numeric string of the requested length.
     *
     * @param length number of random digits to produce
     * @return numeric string containing exactly {@code length} digits
     */
    private String randomDigits(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(secureRandom.nextInt(10));
        }
        return builder.toString();
    }
}
