package com.banka1.exchangeService.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI konfiguracija za exchange-service.
 */
@Configuration
public class SwaggerConfig {

    private static final String APP_TITLE = "Exchange Service API";
    private static final String APP_DESCRIPTION = "API for local exchange rate storage, fetching, and client currency conversion";
    private static final String APP_VERSION = "1.0";

    /**
     * Registruje osnovne OpenAPI metapodatke i JWT bearer shemu.
     *
     * @return OpenAPI opis servisa
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .info(new Info()
                        .title(APP_TITLE)
                        .description(APP_DESCRIPTION)
                        .version(APP_VERSION));
    }
}
