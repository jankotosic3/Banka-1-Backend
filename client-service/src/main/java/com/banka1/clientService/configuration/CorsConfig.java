package com.banka1.clientService.configuration;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Konfiguracija CORS politike za client-service.
 * Dozvoljava zahteve sa Angular frontend aplikacije tokom razvoja.
 */
@Configuration
public class CorsConfig {

    /**
     * Registruje CORS mapiranja koja dozvoljavaju Angular dev serveru da poziva API.
     *
     * @return konfigurator koji primenjuje CORS pravila na sve putanje
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NonNull CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins("http://localhost:4200")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
