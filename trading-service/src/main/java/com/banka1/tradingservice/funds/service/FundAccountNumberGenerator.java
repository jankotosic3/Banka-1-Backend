package com.banka1.tradingservice.funds.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generator za 19-cifrene RSD racune fondova (PR_04 C4.7).
 *
 * <p>Banking-core ima slican generator za korisnicke racune; trading-service
 * ne moze da ga importuje (cross-modul), pa drzi svoju lightweight implementaciju
 * koja generise 18 random cifara + mod-11 check-digit.
 */
@Component
public class FundAccountNumberGenerator {

    private final SecureRandom random = new SecureRandom();

    /** 18 random + 1 check-digit = 19 cifara. Mod-11 check-digit (kao ISO 11649 lite). */
    public String generate() {
        StringBuilder sb = new StringBuilder(19);
        for (int i = 0; i < 18; i++) sb.append(random.nextInt(10));
        sb.append(checkDigit(sb.toString()));
        return sb.toString();
    }

    private int checkDigit(String prefix) {
        long sum = 0;
        int weight = 2;
        for (int i = prefix.length() - 1; i >= 0; i--) {
            sum += (prefix.charAt(i) - '0') * weight;
            weight = (weight == 7) ? 2 : weight + 1;
        }
        int rem = (int) (sum % 11);
        int cd = 11 - rem;
        return cd >= 10 ? 0 : cd;
    }
}
