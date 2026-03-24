package com.banka1.card_service.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuhnServiceTest {

    private final LuhnService luhnService = new LuhnService();

    @Test
    void calculateCheckDigitReturnsExpectedDigit() {
        assertEquals(1, luhnService.calculateCheckDigit("411111111111111"));
        assertEquals(5, luhnService.calculateCheckDigit("37828224631000"));
    }

    @Test
    void isValidReturnsTrueForKnownValidCardNumbers() {
        assertTrue(luhnService.isValid("4111111111111111"));
        assertTrue(luhnService.isValid("5555555555554444"));
        assertTrue(luhnService.isValid("378282246310005"));
    }

    @Test
    void isValidReturnsFalseForInvalidCardNumbers() {
        assertFalse(luhnService.isValid("4111111111111112"));
        assertFalse(luhnService.isValid("abcdef"));
        assertFalse(luhnService.isValid(""));
    }

    @Test
    void calculateCheckDigitRejectsNonNumericPayload() {
        assertThrows(IllegalArgumentException.class, () -> luhnService.calculateCheckDigit("123A"));
    }
}
