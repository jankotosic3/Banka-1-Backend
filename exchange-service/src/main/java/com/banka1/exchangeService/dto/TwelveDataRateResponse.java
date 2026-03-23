package com.banka1.exchangeService.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Interni DTO model (samo za Backend) da bismo imali uredan
 * Java objekat nakon što parsiramo odgovor od Twelve Data.
 *
 * Znaci flow je:
 *   1. pošaljemo HTTP request ka Twelve Data
 *   2. dobijemo JSON odgovor
 *   3. iz tog JSON-a izvučemo bitna polja
 *   4. spakujemo ih u TwelveDataRateResponse
 *   5. onda servis dalje radi sa tim objektom
 *
 * @param fromCurrency izvorna valuta iz trazenog para
 * @param toCurrency   ciljna valuta iz trazenog para
 * @param rate         market kurs koji provider vraca za dati valutni par
 * @param date         datum snapshot-a izveden iz provider timestamp-a ili UTC trenutka fetch-a
 */
public record TwelveDataRateResponse(
        String fromCurrency,
        String toCurrency,
        BigDecimal rate,
        LocalDate date
) {
}
