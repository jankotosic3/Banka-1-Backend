package com.banka1.bankingcore.card.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Integracija MasterCard FX fee-ova u card transaction flow (PR_11 C11.14 real).
 *
 * <p>CardTransactionService zove ovaj applier-a kada je transakcija FX (originCurrency
 * != cardCurrency). Vraca ukupan iznos koji se naplacuje sa kartice (transaction +
 * fee), tako da se debitovanje sa account-a vrsi sa pravim totalom.
 *
 * <p>VISA i Maestro nemaju ekvivalentne fee-ove u spec-u, pa se vraca samo originalni
 * iznos. Ovo je hook tacka — ako se kasnije doda VISA fee, samo se ovde overajduje.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CardFxFeeApplier {

    private final MasterCardFeeCalculator masterCardFeeCalculator;

    /**
     * @param cardBrand "MASTERCARD" | "VISA" | "MAESTRO" | itd.
     * @param transactionAmount iznos transakcije u valuti transakcije.
     * @param originCurrency valuta transakcije (npr. "EUR").
     * @param cardCurrency valuta kartice (npr. "RSD").
     * @param eurToTxRate EUR-prema-tx-valuti kurs (npr. 117.50 za EUR-RSD; 1.0 ako je tx vec u EUR-u).
     * @return iznos koji treba debitovati sa account-a (originalni + fee).
     */
    public BigDecimal applyFxFee(String cardBrand, BigDecimal transactionAmount,
                                  String originCurrency, String cardCurrency, BigDecimal eurToTxRate) {

        if (originCurrency == null || cardCurrency == null || originCurrency.equalsIgnoreCase(cardCurrency)) {
            // Nije FX transakcija
            return transactionAmount;
        }

        BigDecimal fee = BigDecimal.ZERO;
        if ("MASTERCARD".equalsIgnoreCase(cardBrand)) {
            fee = masterCardFeeCalculator.calculateFee(transactionAmount, eurToTxRate);
            log.info("MasterCard FX fee primenjena: tx={} fee={} ({} -> {})",
                    transactionAmount, fee, originCurrency, cardCurrency);
        }
        // VISA, Maestro, AmEx — TBD specijalizovani calculatori; trenutno bez fee-a.

        return transactionAmount.add(fee);
    }
}
