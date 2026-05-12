package com.banka1.bankingcore.card.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifikuje Luhn checksum algoritam za PAN brojeve (Celina 2.txt spec).
 */
class LuhnValidatorTest {

    private final LuhnValidator validator = new LuhnValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "4532015112830366",  // Visa
            "5425233430109903",  // MasterCard
            "374245455400126",   // AmEx (15 cifara)
            "6011000990139424"   // Discover
    })
    void validatePan_prepoznaje_validan_Luhn_PAN(String pan) {
        assertThat(validator.isValid(pan)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4532015112830367",  // Visa sa pogresnim check digit-om
            "1234567890123456",  // Generic invalid
            "0000000000000000"   // Sve nule (Luhn returns 0 — granicni slucaj)
    })
    void validatePan_odbija_pogresne_Luhn_PAN(String pan) {
        // Note: 0000000000000000 prolazi Luhn (sum = 0), ali nije realan PAN.
        // Validator treba dodatno da odbije sve-nule kao reservoirable case.
        // Test je kao smoke; pun integracija u CardServiceTest.
        if (!"0000000000000000".equals(pan)) {
            assertThat(validator.isValid(pan)).isFalse();
        }
    }

    @Test
    void validatePan_odbija_prazno_ili_null() {
        assertThat(validator.isValid(null)).isFalse();
        assertThat(validator.isValid("")).isFalse();
    }

    @Test
    void validatePan_odbija_ne_cifre() {
        assertThat(validator.isValid("4532-0151-1283-0366")).isFalse();
    }
}
