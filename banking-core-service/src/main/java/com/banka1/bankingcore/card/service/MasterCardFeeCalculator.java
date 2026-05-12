package com.banka1.bankingcore.card.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * MasterCard FX naknade (PR_05 C5.8).
 *
 * <p>Spec (Celina 2): "MasterCard kartica naplacuje 1.5% provizije na svaku FX
 * transakciju + fiksnu network fee od 0.30 EUR za prelaze kursa". Kalkulacija
 * koja se primenjuje u CardTransactionService kada {@code cardBrand == MASTERCARD}
 * i {@code originCurrency != cardCurrency}.
 *
 * <p>Iznosi se konfigurisu preko application properties da bi se brzo prilagodilo
 * eventualnim promenama MasterCard provizija.
 */
@Component
public class MasterCardFeeCalculator {

    @Value("${card.mastercard.fx-fee-percent:0.015}")
    private BigDecimal fxFeePercent;

    @Value("${card.mastercard.fx-network-fee-eur:0.30}")
    private BigDecimal networkFeeEur;

    /**
     * Vraca dodatni iznos koji treba skinuti sa kartice u valuti transakcije.
     *
     * @param transactionAmount iznos transakcije u valuti transakcije.
     * @param eurToTxRate       koliko 1 EUR vredi u valuti transakcije (za network fee preracun).
     */
    public BigDecimal calculateFee(BigDecimal transactionAmount, BigDecimal eurToTxRate) {
        BigDecimal percentFee = transactionAmount.multiply(fxFeePercent).setScale(2, RoundingMode.HALF_UP);
        BigDecimal networkFee = networkFeeEur.multiply(eurToTxRate).setScale(2, RoundingMode.HALF_UP);
        return percentFee.add(networkFee);
    }
}
