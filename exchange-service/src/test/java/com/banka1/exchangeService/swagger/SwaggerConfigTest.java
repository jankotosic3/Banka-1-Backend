package com.banka1.exchangeService.swagger;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testovi za staticku OpenAPI konfiguraciju.
 * Ako ovi testovi prolaze, Swagger/OpenAPI bean izbacuje osnovne metapodatke
 * i security semu koju koriste klijenti i gateway dokumentacija.
 */
class SwaggerConfigTest {

    /**
     * Proverava da OpenAPI opis sadrzi naslov servisa i bearer security schemu.
     * Prolaz znaci da ce generisana dokumentacija pravilno prikazati JWT zastitu.
     * Pad znaci da je Swagger konfiguracija promenjena ili nepotpuna.
     */
    @Test
    void openApiContainsBearerSecurityScheme() {
        OpenAPI openAPI = new SwaggerConfig().openAPI();

        assertThat(openAPI.getInfo().getTitle()).isEqualTo("Exchange Service API");
        assertThat(openAPI.getComponents().getSecuritySchemes()).containsKey("bearerAuth");
    }
}
