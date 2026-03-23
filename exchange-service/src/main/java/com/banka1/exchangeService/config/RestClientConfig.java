package com.banka1.exchangeService.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Konfiguracija HTTP klijenta za komunikaciju sa eksternim servisima.
 */
@Configuration
public class RestClientConfig {

    /**
     * Kreira namenski {@link RestClient} za Twelve Data API.
     *
     * @param properties konfiguracija exchange-rate integracije
     * @return konfigurisan RestClient sa baznim URL-om
     */
    @Bean
    public RestClient twelveDataRestClient(ExchangeRateProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.twelveDataBaseUrl())
                .build();
    }
}
