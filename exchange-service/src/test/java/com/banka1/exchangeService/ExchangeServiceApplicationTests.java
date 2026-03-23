package com.banka1.exchangeService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test za podizanje Spring Boot konteksta.
 * Ako test prolazi, osnovna konfiguracija servisa, bean-ovi i auto-config
 * mogu da se ucitaju zajedno pod test profilom.
 *
 * Za brzo rucno pokretanje svih testova uneti komandu: .\gradlew.bat :exchange-service:test --no-daemon
 */
@SpringBootTest
@ActiveProfiles("test")
class ExchangeServiceApplicationTests {

    /**
     * Potvrdjuje da se aplikacioni kontekst uspesno podize.
     * Prolaz znaci da nema kriticnih problema u konfiguraciji.
     * Pad obicno ukazuje na pokvaren bean wiring, property ili startup config.
     */
    @Test
    void contextLoads() {
    }
}
