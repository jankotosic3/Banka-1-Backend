package com.banka1.bankingcore.transaction.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * REST klijent ka clearing-house API-ju za externe transfere
 * (TARGET2/SWIFT/SEPA, zavisno od valute) — PR_13 C13.1.
 *
 * <p>U dev-u se konfigurise prema mock URL-u
 * ({@code clearing-house.url=http://localhost:9999/clearing}); u prod-u ide na pravi
 * clearing-house gateway. Resilience4j circuit breaker + retry su iz
 * {@link com.banka1.security.config.Resilience4jConfig} (PR_06 C6.6).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClearingHouseClient {

    private static final String CB_NAME = "clearing-house";

    @Value("${clearing-house.url:http://localhost:9999/clearing}")
    private String baseUrl;

    @Value("${clearing-house.api-token:}")
    private String apiToken;

    private WebClient webClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @CircuitBreaker(name = CB_NAME)
    @Retry(name = CB_NAME)
    public IssueResult issueTransfer(Long transferId, BigDecimal amount, String currency, String recipientAccount) {
        log.info("Calling clearing-house: transferId={} amount={} {} recipient={}",
                transferId, amount, currency, recipientAccount);
        try {
            return webClient().post()
                    .uri("/transfers")
                    .header("X-Idempotency-Key", "transfer-" + transferId)
                    .bodyValue(Map.of(
                            "transferId", transferId,
                            "amount", amount,
                            "currency", currency,
                            "recipientAccount", recipientAccount))
                    .retrieve()
                    .bodyToMono(IssueResult.class)
                    .timeout(Duration.ofSeconds(30))   // SWIFT moze dugo
                    .block();
        } catch (Exception ex) {
            log.error("Clearing-house failed za transfer {}: {}", transferId, ex.toString());
            return new IssueResult(false, null, ex.getMessage());
        }
    }

    /**
     * @param success true ako je clearing-house prihvatio nalog za izvrsenje
     * @param clearingHouseRef referenca koju daje clearing-house (npr. SWIFT MT103 message ID)
     * @param failureReason ako !success, razlog odbijanja
     */
    public record IssueResult(boolean success, String clearingHouseRef, String failureReason) {}
}
