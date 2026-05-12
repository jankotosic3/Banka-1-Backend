package com.banka1.bankingcore.card.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardFxFeeApplierTest {

    @Mock private MasterCardFeeCalculator masterCardFeeCalculator;

    @InjectMocks private CardFxFeeApplier applier;

    @Test
    void applyFxFee_zaobilazi_kalkulator_kada_je_ista_valuta() {
        BigDecimal result = applier.applyFxFee(
                "MASTERCARD", new BigDecimal("100"), "RSD", "RSD", new BigDecimal("1"));
        assertThat(result).isEqualByComparingTo("100");
        verify(masterCardFeeCalculator, never()).calculateFee(any(), any());
    }

    @Test
    void applyFxFee_dodaje_fee_za_MASTERCARD_FX() {
        when(masterCardFeeCalculator.calculateFee(any(), any())).thenReturn(new BigDecimal("36.75"));
        BigDecimal result = applier.applyFxFee(
                "MASTERCARD", new BigDecimal("100"), "EUR", "RSD", new BigDecimal("117.50"));
        assertThat(result).isEqualByComparingTo("136.75");
    }

    @Test
    void applyFxFee_ne_naplacuje_VISA_fee() {
        BigDecimal result = applier.applyFxFee(
                "VISA", new BigDecimal("100"), "EUR", "RSD", new BigDecimal("117.50"));
        assertThat(result).isEqualByComparingTo("100");
        verify(masterCardFeeCalculator, never()).calculateFee(any(), any());
    }
}
