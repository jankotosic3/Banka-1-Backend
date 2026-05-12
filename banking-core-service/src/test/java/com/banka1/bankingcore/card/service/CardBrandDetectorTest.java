package com.banka1.bankingcore.card.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifikuje brand detection po PAN prefixu (Celina 2.txt spec):
 *   Visa: pocinje sa 4
 *   MasterCard: 51-55 ili 2221-2720
 *   AmEx: 34 ili 37
 *   Maestro: 50, 56-69
 */
class CardBrandDetectorTest {

    private final CardBrandDetector detector = new CardBrandDetector();

    @Test
    void detect_visa_kad_pocinje_sa_4() {
        assertThat(detector.detect("4532015112830366")).isEqualTo("VISA");
    }

    @Test
    void detect_mastercard_kad_pocinje_sa_51_do_55() {
        assertThat(detector.detect("5425233430109903")).isEqualTo("MASTERCARD");
        assertThat(detector.detect("5105105105105100")).isEqualTo("MASTERCARD");
    }

    @Test
    void detect_amex_kad_pocinje_sa_34_ili_37() {
        assertThat(detector.detect("374245455400126")).isEqualTo("AMEX");
        assertThat(detector.detect("340000000000009")).isEqualTo("AMEX");
    }

    @Test
    void detect_unknown_za_nepoznat_prefix() {
        assertThat(detector.detect("9999000000000000")).isEqualTo("UNKNOWN");
    }
}
