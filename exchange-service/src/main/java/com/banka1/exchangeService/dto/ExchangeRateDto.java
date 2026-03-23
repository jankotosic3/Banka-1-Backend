package com.banka1.exchangeService.dto;

import com.banka1.exchangeService.domain.SupportedCurrency;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * DTO za prikaz lokalno sacuvanog kursa.
 * Ovo je DTO koji vracamo kada neko trazi kurs iz sistema, tj. “kako izgleda jedan sacuvani kurs u odgovoru”.
 *
 * @param currencyCode troslovni ISO kod valute (npr. RSD)
 * @param buyingRate   kurs po kojem banka kupuje valutu od klijenta
 * @param sellingRate  kurs po kojem banka prodaje valutu klijentu
 * @param date         datum vazenja kursa
 * @param createdAt    vreme prvog cuvanja reda u bazi
 */
public record ExchangeRateDto(
        String currencyCode,
        BigDecimal buyingRate,
        BigDecimal sellingRate,
        LocalDate date,
        Instant createdAt
) {

    /**
     * Kreira sinteticki zapis za baznu valutu jer RSD ne zahteva eksterni fetch.
     *
     * @param date         datum snapshot-a
     * @return sinteticki DTO sa kursom 1:1
     */
    public static ExchangeRateDto baseCurrency(LocalDate date) {
        return new ExchangeRateDto(SupportedCurrency.RSD.name(), BigDecimal.ONE, BigDecimal.ONE, date, Instant.now());
    }
}
