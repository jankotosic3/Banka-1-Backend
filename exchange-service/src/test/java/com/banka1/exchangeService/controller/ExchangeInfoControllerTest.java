package com.banka1.exchangeService.controller;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testovi pomocnog info endpoint-a.
 * Ovaj endpoint se koristi za brzu proveru identiteta servisa i gateway
 * prefiksa, pa testovi potvrđuju da se oba slucaja ponasaju predvidivo.
 */
class ExchangeInfoControllerTest {

    private final ExchangeInfoController exchangeInfoController = new ExchangeInfoController();

    /**
     * Proverava podrazumevano ponasanje kada gateway ne prosledi
     * `X-Forwarded-Prefix` header.
     * Prolaz znaci da servis sam vraca smislen podrazumevani prefiks
     * `/api/exchange`.
     */
    @Test
    void infoReturnsDefaultGatewayPrefixWhenHeaderIsMissing() {
        Map<String, Object> response = exchangeInfoController.info(null);

        assertThat(response.get("service")).isEqualTo("exchange-service");
        assertThat(response.get("gatewayPrefix")).isEqualTo("/api/exchange");
    }

    /**
     * Proverava da se prosledjeni gateway prefiks odrazava u odgovoru.
     * Prolaz znaci da info endpoint moze verno da prikaze realan reverse proxy
     * prefiks okruzenja u kom radi.
     */
    @Test
    void infoUsesForwardedPrefixWhenHeaderExists() {
        Map<String, Object> response = exchangeInfoController.info("/custom");

        assertThat(response.get("gatewayPrefix")).isEqualTo("/custom");
    }
}
