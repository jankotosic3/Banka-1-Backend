package com.banka1.exchangeService.domain;

import com.banka1.exchangeService.exception.BusinessException;
import com.banka1.exchangeService.exception.ErrorCode;

import java.util.Arrays;
import java.util.List;

/**
 * Eksplicitno podrzane valute exchange-service domena.
 * Enum sluzi kao centralno mesto za validaciju ulaznih valutnih kodova
 * i za definisanje koje strane valute se fetchuju iz eksternog API-ja.
 */
public enum SupportedCurrency {
    RSD,
    EUR,
    CHF,
    USD,
    GBP,
    JPY,
    CAD,
    AUD;

    /**
     * Parsira korisnicki ili API ulaz u podrzanu enum vrednost.
     *
     * @param currencyCode troslovni ISO kod valute
     * @return podrzana valuta iz domena
     */
    public static SupportedCurrency from(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_CURRENCY, "Kod valute je obavezan.");
        }

        try {
            return SupportedCurrency.valueOf(currencyCode.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    ErrorCode.UNSUPPORTED_CURRENCY,
                    "UNSUPPORTED CURRENCY"
            );
        }
    }

    /**
     * Vraca valute za koje servis radi eksterni fetch dnevnog kursa.
     * RSD je iskljucen jer se za njega ne trazi provider kurs.
     * Tehnicki, RSD ima syntetic kurs = 1
     * Pošto je RSD bazna valuta, za nju ne fetchujemo kurs spolja.
     * Zato metoda vraca samo EUR, CHF, USD, GBP, JPY, CAD, AUD
     *
     * @return lista podrzanih stranih valuta za fetch
     */
    public static List<String> trackedCurrencyCodes() {
        return Arrays.stream(values())
                .filter(currency -> currency != RSD)
                .map(Enum::name)
                .toList();
    }
}
