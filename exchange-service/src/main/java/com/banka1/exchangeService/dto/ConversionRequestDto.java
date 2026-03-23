package com.banka1.exchangeService.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Interni DTO koji kontroler prosledjuje servisnom sloju nakon sto preuzme i
 * validira query parametre kalkulacije.
 *
 * @param amount iznos koji se konvertuje
 * @param fromCurrency izvorna valuta
 * @param toCurrency ciljna valuta
 * @param date opcioni datum kursne liste
 */
public record ConversionRequestDto(
        BigDecimal amount,
        String fromCurrency,
        String toCurrency,
        LocalDate date
) {
}
