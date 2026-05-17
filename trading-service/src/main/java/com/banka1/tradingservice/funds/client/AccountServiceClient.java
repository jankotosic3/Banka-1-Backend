package com.banka1.tradingservice.funds.client;

import com.banka1.tradingservice.security.JWTService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * REST klijent ka account-service-u (PR_14 C14.8 + PR_15 C15.4).
 *
 * <p>Auth strategija (PR_15):
 * <ol>
 *   <li>Ako u {@code SecurityContextHolder}-u postoji JWT (HTTP request scope) —
 *       forward-uje korisnikov token. Endpoint
 *       {@code POST /internal/accounts/system} prihvata BASIC role.</li>
 *   <li>Ako SecurityContext nema JWT (npr. RabbitMQ listener async kontekst) —
 *       generise sluzbeni JWT preko {@link JWTService} sa role=SERVICE. Endpoint
 *       prihvata SERVICE role.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountServiceClient {

    private final JWTService jwtService;

    @Value("${services.account.url:http://account-service:8082}")
    private String baseUrl;

    private WebClient webClient(String bearerToken) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public CreatedSystemAccount createSystemAccount(
            String accountNumber, Long ownerId, String currency,
            String displayName, BigDecimal initialBalance) {

        log.info("[account-service] createSystemAccount accountNumber={} ownerId={} currency={}",
                accountNumber, ownerId, currency);

        return webClient(jwtService.generateJwtToken()).post()
                .uri("/internal/accounts/system")
                .bodyValue(Map.of(
                        "accountNumber", accountNumber,
                        "ownerId", ownerId,
                        "currencyCode", currency,
                        "accountConcrete", "STANDARDNI",
                        "displayName", displayName,
                        "initialBalance", initialBalance != null ? initialBalance : BigDecimal.ZERO))
                .retrieve()
                .bodyToMono(CreatedSystemAccount.class)
                .timeout(Duration.ofSeconds(10))
                .block();
    }

    public void creditAccount(String accountNumber, BigDecimal amount, Long ownerId) {
        log.info("[account-service] creditAccount accountNumber={} ownerId={} amount={}",
                accountNumber, ownerId, amount);

        webClient(jwtService.generateJwtToken()).post()
                .uri("/internal/accounts/credit")
                .bodyValue(Map.of(
                        "accountNumber", accountNumber,
                        "amount", amount,
                        "clientId", ownerId))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .block();
    }

    public AccountDetails getByNumber(String accountNumber) {
        return webClient(currentBearerOrServiceToken()).get()
                .uri("/internal/accounts/{accountNumber}/details", accountNumber)
                .retrieve()
                .bodyToMono(AccountDetails.class)
                .timeout(Duration.ofSeconds(10))
                .block();
    }

    /**
     * Vraca korisnicki JWT iz SecurityContext-a, ili pala-back-uje na sluzbeni
     * SERVICE token ako konteksta nema (npr. RabbitMQ listener).
     */
    private String currentBearerOrServiceToken() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && jwtAuth.getToken() instanceof Jwt jwt) {
            return jwt.getTokenValue();
        }
        log.debug("Nema korisnikovog JWT-a u SecurityContext-u — generisem sluzbeni SERVICE token.");
        return jwtService.generateJwtToken();
    }

    public record CreatedSystemAccount(
            Long id, String accountNumber, Long ownerId, String currency,
            BigDecimal availableBalance, String status, String accountType
    ) {}

    public record AccountDetails(
            Long id, String accountNumber, Long ownerId, String currency,
            BigDecimal availableBalance, String status, String accountType
    ) {}
}
