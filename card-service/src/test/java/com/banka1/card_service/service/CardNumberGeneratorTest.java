package com.banka1.card_service.service;

import com.banka1.card_service.domain.enums.CardBrand;
import com.banka1.card_service.exception.BusinessException;
import com.banka1.card_service.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardNumberGeneratorTest {

    @Mock
    private CardRepository cardRepository;

    private CardNumberGenerator cardNumberGenerator;

    @BeforeEach
    void setUp() {
        cardNumberGenerator = new CardNumberGenerator(cardRepository, new LuhnService());
    }

    @ParameterizedTest
    @EnumSource(CardBrand.class)
    void generateCardNumberReturnsBrandSpecificValidNumber(CardBrand cardBrand) {
        when(cardRepository.existsByCardNumber(anyString())).thenReturn(false);

        String cardNumber = cardNumberGenerator.generateCardNumber(cardBrand);

        assertEquals(cardBrand.getCardNumberLength(), cardNumber.length());
        assertTrue(cardNumberGenerator.isValidForBrand(cardNumber, cardBrand));
    }

    @ParameterizedTest
    @MethodSource("brandPrefixes")
    void generateCardNumberUsesExpectedIssuerPrefix(CardBrand cardBrand, String prefixType) {
        when(cardRepository.existsByCardNumber(anyString())).thenReturn(false);

        String cardNumber = cardNumberGenerator.generateCardNumber(cardBrand);

        switch (prefixType) {
            case "VISA" -> assertTrue(cardNumber.startsWith("4"));
            case "DINACARD" -> assertTrue(cardNumber.startsWith("9891"));
            case "AMEX" -> assertTrue(cardNumber.startsWith("34") || cardNumber.startsWith("37"));
            case "MASTERCARD" -> assertTrue(hasValidMastercardPrefix(cardNumber));
            default -> throw new IllegalStateException("Unexpected brand assertion: " + prefixType);
        }
    }

    @Test
    void generateCardNumberThrowsWhenAllAttemptsCollide() {
        when(cardRepository.existsByCardNumber(anyString())).thenReturn(true);

        assertThrows(BusinessException.class, () -> cardNumberGenerator.generateCardNumber(CardBrand.VISA));
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> brandPrefixes() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(CardBrand.VISA, "VISA"),
                org.junit.jupiter.params.provider.Arguments.of(CardBrand.MASTERCARD, "MASTERCARD"),
                org.junit.jupiter.params.provider.Arguments.of(CardBrand.DINACARD, "DINACARD"),
                org.junit.jupiter.params.provider.Arguments.of(CardBrand.AMEX, "AMEX")
        );
    }

    private boolean hasValidMastercardPrefix(String cardNumber) {
        int firstTwoDigits = Integer.parseInt(cardNumber.substring(0, 2));
        int firstFourDigits = Integer.parseInt(cardNumber.substring(0, 4));
        return (firstTwoDigits >= 51 && firstTwoDigits <= 55)
                || (firstFourDigits >= 2221 && firstFourDigits <= 2720);
    }
}
