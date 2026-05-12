package com.banka1.bankingcore.card.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MasterCardFeeCalculatorTest {

    private final MasterCardFeeCalculator calc = new MasterCardFeeCalculator();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(calc, "fxFeePercent", new BigDecimal("0.015"));
        ReflectionTestUtils.setField(calc, "networkFeeEur", new BigDecimal("0.30"));
    }

    @Test
    void calculateFee_za_100EUR_uz_kurs_117_5_RSD_EUR() {
        // percentFee = 100 * 0.015 = 1.50
        // networkFee = 0.30 * 117.5 = 35.25
        // total = 36.75
        BigDecimal fee = calc.calculateFee(new BigDecimal("100"), new BigDecimal("117.50"));
        assertThat(fee).isEqualByComparingTo("36.75");
    }

    @Test
    void calculateFee_za_1000EUR_uz_kurs_1_kada_je_tx_u_eurima() {
        // percentFee = 1000 * 0.015 = 15.00
        // networkFee = 0.30 * 1 = 0.30
        // total = 15.30
        BigDecimal fee = calc.calculateFee(new BigDecimal("1000"), BigDecimal.ONE);
        assertThat(fee).isEqualByComparingTo("15.30");
    }

    @Test
    void calculateFee_zaokruzuje_2_decimale() {
        BigDecimal fee = calc.calculateFee(new BigDecimal("33.33"), new BigDecimal("100"));
        assertThat(fee.scale()).isEqualTo(2);
    }
}
