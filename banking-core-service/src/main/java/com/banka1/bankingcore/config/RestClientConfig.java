package com.banka1.bankingcore.config;

import com.banka1.bankingcore.security.JWTService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * Definise RestClient bean-ove ka okolnim servisima (PR_14 C14.4).
 *
 * <p>Banking-core zove account-service za debit/credit operacije, a market-service
 * za interne FX kalkulacije kod cross-currency settlement-a.
 *
 * <p>Profil "!local" je iskljucen radi paritet-a sa order-service-om: u local profilu
 * neke od dependency property-ja nisu setovane (services.account.url) pa bi bean
 * fail-ovao u kontekstu lokalnog testa.
 */
@Configuration
@RequiredArgsConstructor
@Profile("!local")
public class RestClientConfig {

    private final JWTService jwtService;

    @Value("${banka.security.expiration-time:3600000}")
    private long tokenValidityMillis;

    @Bean
    public ClientHttpRequestInterceptor jwtAuthInterceptor() {
        return new ServiceJwtAuthInterceptor(jwtService, tokenValidityMillis);
    }

    @Bean
    public RestClient.Builder restClientBuilder(ClientHttpRequestInterceptor jwtAuthInterceptor) {
        return RestClient.builder()
                .requestInterceptor(jwtAuthInterceptor);
    }

    @Bean
    public RestClient accountRestClient(RestClient.Builder builder, @Value("${services.account.url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }

    @Bean
    public RestClient marketRestClient(RestClient.Builder builder, @Value("${services.exchange.url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
