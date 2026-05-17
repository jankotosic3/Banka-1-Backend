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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * REST klijent ka market-service price feed-u (PR_18 C18.1).
 *
 * <p>Pre PR_18: FundLiquidationService je koristio {@code FundHolding.avgUnitPrice}
 * (istorijska prosecna cena kupovine) kao proxy za "trzisnu cenu" pri likvidaciji.
 * To je domenski netacno — fond ne dobija avg cost, dobija current market price.
 *
 * <p>Posle PR_18: ovaj klijent povlaci current price preko
 * {@code GET /stocks/price-feed/current?tickers=...} i FundLiquidationService
 * koristi te cene. "Sell" je i dalje simuliran (nema pravog order book matching-a),
 * ali iznos je realisticna market value.
 *
 * <p>Auth: forward-uje korisnicki JWT iz SecurityContext-a; fall-back na sluzbeni
 * SERVICE token za RabbitMQ async kontekst (isto kao AccountServiceClient C15.4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketPriceClient {

    private final JWTService jwtService;

    @Value("${services.market.url:http://market-service:8085}")
    private String baseUrl;

    private WebClient webClient(String bearerToken) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Vraca trenutne cene za sve zadate ticker-e (batch poziv).
     *
     * <p>Tolerantno na greske: ako market-service ne odgovori, vraca prazan rezultat
     * — caller (FundLiquidationService) ce fall-back-ovati na avgUnitPrice.
     */
    public Map<String, BigDecimal> currentPrices(List<String> tickers) {
        return fetchSnapshots(tickers).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().currentPrice()));
    }

    public Map<String, StockPrice> fetchSnapshots(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) {
            return Map.of();
        }
        try {
            String token = currentBearerOrServiceToken();
            String tickerCsv = String.join(",", tickers);
            List<StockPrice> prices = webClient(token).get()
                    .uri(uriBuilder -> uriBuilder.path("/stocks/price-feed/current")
                            .queryParam("tickers", tickerCsv).build())
                    .retrieve()
                    .bodyToFlux(StockPrice.class)
                    .collectList()
                    .timeout(Duration.ofSeconds(5))
                    .block();
            if (prices == null) {
                return Map.of();
            }
            return prices.stream()
                    .filter(p -> p.ticker() != null && p.currentPrice() != null)
                    .collect(Collectors.toMap(StockPrice::ticker, p -> p, (a, b) -> a));
        } catch (Exception ex) {
            log.warn("Market price fetch failed: {}", ex.toString());
            return Map.of();
        }
    }

    public Optional<BigDecimal> currentPrice(String ticker) {
        Map<String, BigDecimal> prices = currentPrices(List.of(ticker));
        return Optional.ofNullable(prices.get(ticker));
    }

    public Optional<BigDecimal> convertNoCommission(BigDecimal amount, String fromCurrency, String toCurrency) {
        if (amount == null) {
            return Optional.empty();
        }
        if (amount.signum() == 0 || fromCurrency == null || toCurrency == null
                || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return Optional.of(amount);
        }
        try {
            String token = currentBearerOrServiceToken();
            ConversionResponse response = webClient(token).get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/calculate/no-commission")
                            .queryParam("fromCurrency", fromCurrency)
                            .queryParam("toCurrency", toCurrency)
                            .queryParam("amount", amount.toPlainString())
                            .build())
                    .retrieve()
                    .bodyToMono(ConversionResponse.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            return Optional.ofNullable(response == null ? null : response.toAmount());
        } catch (Exception ex) {
            log.warn("Market FX conversion failed {} {} -> {}: {}",
                    amount, fromCurrency, toCurrency, ex.toString());
            return Optional.empty();
        }
    }

    private String currentBearerOrServiceToken() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && jwtAuth.getToken() instanceof Jwt jwt) {
            return jwt.getTokenValue();
        }
        return jwtService.generateJwtToken();
    }

    /**
     * Subset of market-service StockPriceSnapshotDto — samo polja koja trading-service
     * koristi. Jackson ignorise unknown polja (default Spring Boot konfiguracija).
     */
    public record StockPrice(String ticker, BigDecimal currentPrice, BigDecimal changePercent, Long volume) {}

    public record ConversionResponse(String fromCurrency,
                                     String toCurrency,
                                     BigDecimal fromAmount,
                                     BigDecimal toAmount,
                                     BigDecimal rate,
                                     BigDecimal commission) {}
}
