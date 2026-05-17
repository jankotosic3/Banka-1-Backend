package com.banka1.saga_orchestrator.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * REST klijent ka {@code trading-service} (PR_15 C15.3).
 *
 * <p>Pre PR_15 saga {@code FundRedeemWithLiquidationSaga} je zvala MarketServiceClient
 * za likvidaciju fonda. Domenski netacno: FundHolding entitet i logika "prodaj N
 * hartija fonda" zivi u trading-service-u, ne u market-service-u (koji drzi samo
 * stock price feed).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingServiceClient {

    private static final String CB_NAME = "trading-service";

    @Value("${services.trading.url:http://trading-service:8088}")
    private String baseUrl;

    @Value("${services.trading.internal-token:}")
    private String internalToken;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private final ObjectMapper objectMapper;

    private WebClient webClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private String bearerToken() {
        if (internalToken != null && !internalToken.isBlank()) {
            return internalToken;
        }
        try {
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("iss", "banka1");
            payload.put("sub", "saga-orchestrator-service");
            payload.put("id", -999L);
            payload.put("roles", "SERVICE");
            payload.put("permissions", java.util.List.of());
            payload.put("exp", Instant.now().plus(Duration.ofMinutes(10)).getEpochSecond());

            String unsigned = base64Url(objectMapper.writeValueAsBytes(header))
                    + "." + base64Url(objectMapper.writeValueAsBytes(payload));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return unsigned + "." + base64Url(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Ne mogu da generisem SERVICE JWT za trading-service.", ex);
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * FUND_LIQUIDATION_FOR_REDEMPTION step 1: trading-service likvidira hartije fonda
     * dok ne pokrije zadati iznos. Posto FundHolding entitet zivi u trading-service-u
     * (PR_14 C14.7), endpoint je tamo, ne u market-service-u.
     */
    @CircuitBreaker(name = CB_NAME)
    @Retry(name = CB_NAME)
    public LiquidationResult liquidateForFund(Long fundId, BigDecimal targetAmount, String correlationId) {
        log.info("[trading-service] liquidateForFund fund={} amount={} correlationId={}",
                fundId, targetAmount, correlationId);
        return webClient().post()
                .uri("/funds/internal/{fundId}/liquidate", fundId)
                .header("X-Correlation-Id", correlationId)
                .bodyValue(Map.of("targetAmount", targetAmount))
                .retrieve()
                .bodyToMono(LiquidationResult.class)
                .timeout(Duration.ofSeconds(30))
                .block();
    }

    /** OTC_EXERCISE Step 2: rezervacija akcija prodavca. */
    @CircuitBreaker(name = CB_NAME)
    @Retry(name = CB_NAME)
    public StockReservationResult reserveStocks(Long sellerId, String stockTicker, int amount, String correlationId) {
        log.info("[trading-service] reserveStocks seller={} ticker={} amount={} correlationId={}",
                sellerId, stockTicker, amount, correlationId);
        return webClient().post()
                .uri("/stocks/internal/reserve")
                .header("X-Correlation-Id", correlationId)
                .bodyValue(Map.of("ownerId", sellerId, "stockTicker", stockTicker, "amount", amount))
                .retrieve()
                .bodyToMono(StockReservationResult.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    /** Kompenzacija Step 2: oslobadja rezervisane akcije. */
    @CircuitBreaker(name = CB_NAME)
    @Retry(name = CB_NAME)
    public void releaseStocks(String reservationId, String correlationId) {
        log.info("[trading-service] releaseStocks reservation={} correlationId={}", reservationId, correlationId);
        webClient().delete()
                .uri("/stocks/internal/reservations/{id}", reservationId)
                .header("X-Correlation-Id", correlationId)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    /** OTC_EXERCISE Step 4: transfer vlasnistva akcija na kupca. */
    @CircuitBreaker(name = CB_NAME)
    @Retry(name = CB_NAME)
    public OwnershipTransferResult transferOwnership(String reservationId, Long buyerId, String correlationId) {
        log.info("[trading-service] transferOwnership reservation={} buyer={} correlationId={}",
                reservationId, buyerId, correlationId);
        return webClient().post()
                .uri("/stocks/internal/reservations/{id}/transfer", reservationId)
                .header("X-Correlation-Id", correlationId)
                .bodyValue(Map.of("buyerId", buyerId))
                .retrieve()
                .bodyToMono(OwnershipTransferResult.class)
                .timeout(Duration.ofSeconds(10))
                .block();
    }

    /** Kompenzacija Step 4: vraca vlasnistvo prodavcu. */
    @CircuitBreaker(name = CB_NAME)
    @Retry(name = CB_NAME)
    public void reverseOwnership(String ownershipTransferId, String correlationId) {
        log.info("[trading-service] reverseOwnership id={} correlationId={}", ownershipTransferId, correlationId);
        webClient().post()
                .uri("/stocks/internal/ownership-transfers/{id}/reverse", ownershipTransferId)
                .header("X-Correlation-Id", correlationId)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(5))
                .block();
    }

    public record LiquidationResult(String liquidationId, BigDecimal liquidatedAmount, int holdingsSold) {}
    public record StockReservationResult(String reservationId, String status) {}
    public record OwnershipTransferResult(String ownershipTransferId, String status) {}
}
