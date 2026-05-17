package com.banka1.marketservice.stock.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * REST klijent za AlphaVantage Quote API (PR_13 C13.2).
 *
 * <p>Endpoint: {@code GET /query?function=GLOBAL_QUOTE&symbol={ticker}&apikey={key}}
 *
 * <p>Response (JSON):
 * <pre>
 * {
 *   "Global Quote": {
 *     "01. symbol": "IBM",
 *     "02. open": "150.00",
 *     "03. high": "152.50",
 *     "04. low": "149.20",
 *     "05. price": "151.30",
 *     "06. volume": "1234567",
 *     "07. latest trading day": "2026-05-08",
 *     "08. previous close": "150.10",
 *     "09. change": "1.20",
 *     "10. change percent": "0.7995%"
 *   }
 * }
 * </pre>
 *
 * <p>Free tier: 5 zahteva/min, 500 zahteva/dan. {@link com.banka1.marketservice.stock.service.StockPriceFeedService}
 * 15s cache je dovoljan da ne probijemo limit pri tipicnoj browser polling stopi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlphaVantageClient {

    private static final String CB_NAME = "alpha-vantage";

    @Value("${alpha.vantage.base-url:https://www.alphavantage.co}")
    private String baseUrl;

    @Value("${alpha.vantage.api.key:}")
    private String apiKey;

    private WebClient webClient() {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = CB_NAME)
    @Retry(name = CB_NAME)
    @SuppressWarnings("unchecked")
    public Quote fetchQuote(String ticker) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("AlphaVantage API key nije konfigurisan — vracam null (StockPriceFeedService ce koristiti mock).");
            return null;
        }
        try {
            Map<String, Object> response = webClient().get()
                    .uri(uriBuilder -> uriBuilder.path("/query")
                            .queryParam("function", "GLOBAL_QUOTE")
                            .queryParam("symbol", ticker)
                            .queryParam("apikey", apiKey)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response == null) return null;
            Map<String, String> globalQuote = (Map<String, String>) response.get("Global Quote");
            if (globalQuote == null || globalQuote.isEmpty()) {
                log.warn("AlphaVantage vratio prazan Global Quote za {}", ticker);
                return null;
            }
            return new Quote(
                    globalQuote.get("01. symbol"),
                    parseBigDecimal(globalQuote.get("05. price")),
                    parseBigDecimal(globalQuote.get("02. open")),
                    parseBigDecimal(globalQuote.get("08. previous close")),
                    parseChangePercent(globalQuote.get("10. change percent")),
                    parseLong(globalQuote.get("06. volume"))
            );
        } catch (Exception ex) {
            log.error("AlphaVantage fetch failed za {}: {}", ticker, ex.toString());
            return null;
        }
    }

    private BigDecimal parseBigDecimal(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private BigDecimal parseChangePercent(String s) {
        if (s == null) return null;
        // "0.7995%" -> "0.7995"
        String stripped = s.endsWith("%") ? s.substring(0, s.length() - 1) : s;
        return new BigDecimal(stripped);
    }

    private Long parseLong(String s) {
        try {
            return s == null ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Quote(String ticker, BigDecimal price, BigDecimal open, BigDecimal previousClose, BigDecimal changePercent, Long volume) {}
}
