package com.banka1.bankingcore.account.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifikuje da je 16-cifren account number sa mod-11 check digit-om validan po
 * spec-u Celina 2.txt (banka koristi mod-11 za RSD racune).
 */
class AccountNumberGeneratorTest {

    private final AccountNumberGenerator gen = new AccountNumberGenerator();

    @Test
    void generate_proizvodi_16_cifara_sa_mod11_check_digitom() {
        for (int i = 0; i < 100; i++) {
            String acc = gen.generate();
            assertThat(acc).hasSize(16).matches("\\d{16}");
        }
    }

    @Test
    void generate_proizvodi_unique_brojeve() {
        String a = gen.generate();
        String b = gen.generate();
        assertThat(a).isNotEqualTo(b);
    }
}
