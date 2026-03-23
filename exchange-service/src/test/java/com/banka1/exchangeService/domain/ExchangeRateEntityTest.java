package com.banka1.exchangeService.domain;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jedinicni testovi JPA entiteta ExchangeRateEntity.
 * Fokus je na lifecycle ponasanju koje entitet sam implementira, bez baze i
 * bez JPA konteksta.
 */
class ExchangeRateEntityTest {

    /**
     * Proverava da `@PrePersist` logika postavlja `createdAt` kada vrednost nije
     * ranije zadana.
     * Prolaz znaci da novi redovi dobijaju vreme kreiranja i bez rucnog setovanja.
     */
    @Test
    void onCreateSetsCreatedAtWhenMissing() {
        ExchangeRateEntity entity = new ExchangeRateEntity();

        entity.onCreate();

        assertThat(entity.getCreatedAt()).isNotNull();
    }

    /**
     * Proverava da lifecycle metoda ne pregazi vec postojecu `createdAt`
     * vrednost.
     * Prolaz znaci da fallback ili migraciona logika mogu bezbedno da zadrze
     * eksplicitno postavljeni timestamp.
     */
    @Test
    void onCreateDoesNotOverwriteExistingCreatedAt() {
        ExchangeRateEntity entity = new ExchangeRateEntity();
        Timestamp existing = Timestamp.from(Instant.parse("2026-03-22T10:15:30Z"));
        entity.setCreatedAt(existing);

        entity.onCreate();

        assertThat(entity.getCreatedAt()).isEqualTo(existing);
    }
}
