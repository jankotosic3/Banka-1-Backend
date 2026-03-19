package com.banka1.clientService.domain.enums;

/**
 * Enum koji definiše moguće uloge klijenta u sistemu.
 * Hijerarhija: {@code CLIENT_TRADING > CLIENT_BASIC}.
 */
public enum ClientRole {

    /** Standardni klijent sa pristupom osnovnim bankarskim funkcionalnostima. */
    CLIENT_BASIC,

    /** Klijent sa pristupom trading funkcionalnostima, ukljucujuci sve privilegije CLIENT_BASIC. */
    CLIENT_TRADING
}
