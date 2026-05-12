package com.banka1.bankingcore.account.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * PR_19 C19.X: AccountNumberGenerator za margin accounts (i potencijalno
 * regular accounts). Spec (Celina 2.txt): RSD racuni koriste 16-cifrene brojeve
 * sa mod-11 check digit-om.
 *
 * <p>Format: 15 random cifara + 1 check digit (mod-11). Pre PR_19 klasa nije
 * postojala u kodu (samo test); MarginAccountService koja je referencira nije
 * mogla da kompilira.
 */
@Component
public class AccountNumberGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Generise 16-cifren broj racuna sa mod-11 check digit-om na poslednjem mestu.
     * Ako mod-11 da check digit 10, koristi 0 kao fallback (standardni mod-11 trick).
     */
    public String generate() {
        StringBuilder body = new StringBuilder(15);
        for (int i = 0; i < 15; i++) {
            body.append(RANDOM.nextInt(10));
        }
        int check = mod11CheckDigit(body.toString());
        return body.append(check).toString();
    }

    private int mod11CheckDigit(String fifteenDigits) {
        int sum = 0;
        int weight = 2;
        // Spec mod-11: weight ide od 2 nadalje, count desno-ka-levo.
        for (int i = fifteenDigits.length() - 1; i >= 0; i--) {
            sum += Character.getNumericValue(fifteenDigits.charAt(i)) * weight;
            weight = (weight == 7) ? 2 : weight + 1;
        }
        int check = 11 - (sum % 11);
        if (check >= 10) {
            check = 0;
        }
        return check;
    }
}
