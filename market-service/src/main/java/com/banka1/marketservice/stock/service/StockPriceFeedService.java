package com.banka1.marketservice.stock.service;

import com.banka1.marketservice.stock.client.AlphaVantageClient;
import com.banka1.marketservice.stock.dto.StockPriceSnapshotDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory price feed sa 15s cache-om (PR_12 C12.1; PR_13 C13.2 dorada sa AlphaVantage realizacijom).
 *
 * <p>Pri prvom pozivu za neki ticker, dohvata sa AlphaVantage Quote API-ja
 * (kroz {@link AlphaVantageClient}). Posle toga 15 s vraca isti rezultat iz cache-a.
 * Ako nema API key-a ili AlphaVantage ne odgovori, vraca dev-mock snapshot tako da
 * frontend OTC vizualizacija nikad ne padne na blank stranici.
 *
 * <p>U produkcionoj implementaciji ovo treba da bude WebSocket feed sa AlphaVantage
 * websocket-a ili sa berze direktno (sa redis cache-om za cross-replica sharing).
 * 15s in-memory cache je pragmatic kompromis — dovoljno brz da frontend ne probija
 * AlphaVantage free-tier limit (5 zahteva/min, 500/dan).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockPriceFeedService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(15);

    private final Map<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    /** Optional AlphaVantage klijent (PR_13 C13.2) — null ako klasa nije na classpath-u ili API key nije konfigurisan. */
    @Autowired(required = false)
    private AlphaVantageClient alphaVantageClient;

    public StockPriceSnapshotDto getCurrentPrice(String ticker) {
        String upper = ticker.toUpperCase();
        CachedSnapshot cached = cache.get(upper);
        if (cached != null && Instant.now().isBefore(cached.expiresAt)) {
            return cached.snapshot;
        }
        StockPriceSnapshotDto fresh = fetchFromUpstream(upper);
        if (fresh != null) {
            cache.put(upper, new CachedSnapshot(fresh, Instant.now().plus(CACHE_TTL)));
        }
        return fresh;
    }

    public List<StockPriceSnapshotDto> getCurrentPrices(List<String> tickers) {
        return tickers.stream()
                .map(this::getCurrentPrice)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Fetch sa AlphaVantage-a (PR_13 C13.2 real integracija).
     *
     * <p>Ako je AlphaVantage klijent dostupan i API key konfigurisan, poziva ga.
     * Ako vrati null (rate-limit, prazan response, network failure), vraca mock
     * snapshot tako da frontend OTC vizualizacija nikad ne padne. Mock je
     * dev-fallback; production deploy mora imati validan API key.
     */
    private StockPriceSnapshotDto fetchFromUpstream(String ticker) {
        log.debug("Fetching price feed za {} (cache miss)", ticker);

        if (alphaVantageClient != null) {
            AlphaVantageClient.Quote quote = alphaVantageClient.fetchQuote(ticker);
            if (quote != null && quote.price() != null) {
                return StockPriceSnapshotDto.builder()
                        .ticker(quote.ticker())
                        .currentPrice(quote.price())
                        .openPrice(quote.open())
                        .previousClose(quote.previousClose())
                        .changePercent(quote.changePercent())
                        .volume(quote.volume())
                        .currency("USD")  // AlphaVantage vraca USD za US listings; multi-currency je TBD
                        .timestamp(Instant.now())
                        .build();
            }
        }

        // Fallback dev-mock kada nema AlphaVantage konekcije.
        log.debug("Vracam dev-mock za {} (AlphaVantage unavailable)", ticker);
        return StockPriceSnapshotDto.builder()
                .ticker(ticker)
                .currentPrice(new BigDecimal("150.25"))
                .openPrice(new BigDecimal("148.00"))
                .previousClose(new BigDecimal("149.50"))
                .changePercent(new BigDecimal("0.50"))
                .volume(1_000_000L)
                .currency("USD")
                .timestamp(Instant.now())
                .build();
    }

    private record CachedSnapshot(StockPriceSnapshotDto snapshot, Instant expiresAt) {}
}
