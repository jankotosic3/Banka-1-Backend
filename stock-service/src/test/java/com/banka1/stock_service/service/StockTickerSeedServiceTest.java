package com.banka1.stock_service.service;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.dto.StockTickerSeedResponse;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockExchangeRepository;
import com.banka1.stock_service.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for {@link StockTickerSeedService}.
 *
 * <p>The seed uses a small built-in ticker list instead of a CSV file, so the tests verify
 * the full persistence flow directly against the in-memory test database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StockTickerSeedServiceTest {

    @Autowired
    private StockTickerSeedService stockTickerSeedService;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private StockExchangeRepository stockExchangeRepository;

    @Test
    void seedDefaultTickersCreatesStocksAndListings() {
        saveExchange("Nasdaq", "NASDAQ", "XNAS");
        saveExchange("New York Portfolio Clearing", "NYPC", "NYPC");
        saveExchange("Chicago Mercantile Exchange", "CME", "XCME");

        StockTickerSeedResponse response = stockTickerSeedService.seedDefaultTickers();

        Stock apple = stockRepository.findByTicker("AAPL").orElseThrow();
        Listing appleListing = listingRepository.findByListingTypeAndSecurityId(ListingType.STOCK, apple.getId())
                .orElseThrow();

        assertThat(response.source()).isEqualTo("built-in starter stock tickers");
        assertThat(response.processedRows()).isEqualTo(10);
        assertThat(response.createdCount()).isEqualTo(10);
        assertThat(response.updatedCount()).isZero();
        assertThat(response.unchangedCount()).isZero();
        assertThat(stockRepository.count()).isEqualTo(10);
        assertThat(listingRepository.count()).isEqualTo(10);

        assertThat(apple.getName()).isEqualTo("Apple Inc.");
        assertThat(apple.getOutstandingShares()).isZero();
        assertThat(apple.getDividendYield()).isEqualByComparingTo("0.0000");

        assertThat(appleListing.getListingType()).isEqualTo(ListingType.STOCK);
        assertThat(appleListing.getStockExchange().getExchangeMICCode()).isEqualTo("XNAS");
        assertThat(appleListing.getTicker()).isEqualTo("AAPL");
        assertThat(appleListing.getName()).isEqualTo("Apple Inc.");
        assertThat(appleListing.getLastRefresh()).isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS));
        assertThat(appleListing.getPrice()).isEqualByComparingTo("0.00000000");
        assertThat(appleListing.getAsk()).isEqualByComparingTo("0.00000000");
        assertThat(appleListing.getBid()).isEqualByComparingTo("0.00000000");
        assertThat(appleListing.getChange()).isEqualByComparingTo("0.00000000");
        assertThat(appleListing.getVolume()).isZero();
    }

    @Test
    void seedDefaultTickersIsIdempotentOnRepeatedRuns() {
        saveExchange("Nasdaq", "NASDAQ", "XNAS");
        saveExchange("New York Portfolio Clearing", "NYPC", "NYPC");
        saveExchange("Chicago Mercantile Exchange", "CME", "XCME");

        stockTickerSeedService.seedDefaultTickers();
        StockTickerSeedResponse secondRun = stockTickerSeedService.seedDefaultTickers();

        assertThat(secondRun.processedRows()).isEqualTo(10);
        assertThat(secondRun.createdCount()).isZero();
        assertThat(secondRun.updatedCount()).isZero();
        assertThat(secondRun.unchangedCount()).isEqualTo(10);
        assertThat(stockRepository.count()).isEqualTo(10);
        assertThat(listingRepository.count()).isEqualTo(10);
    }

    @Test
    void seedDefaultTickersCreatesMissingListingWithoutOverwritingExistingStock() {
        saveExchange("Nasdaq", "NASDAQ", "XNAS");
        saveExchange("New York Portfolio Clearing", "NYPC", "NYPC");
        saveExchange("Chicago Mercantile Exchange", "CME", "XCME");

        Stock existingApple = new Stock();
        existingApple.setTicker("AAPL");
        existingApple.setName("Apple From Provider");
        existingApple.setOutstandingShares(15_000_000_000L);
        existingApple.setDividendYield(new BigDecimal("0.0044"));
        stockRepository.saveAndFlush(existingApple);

        StockTickerSeedResponse response = stockTickerSeedService.seedDefaultTickers();

        Stock persistedApple = stockRepository.findByTicker("AAPL").orElseThrow();
        Listing appleListing = listingRepository.findByListingTypeAndSecurityId(ListingType.STOCK, persistedApple.getId())
                .orElseThrow();

        assertThat(response.processedRows()).isEqualTo(10);
        assertThat(response.createdCount()).isEqualTo(10);
        assertThat(response.updatedCount()).isZero();
        assertThat(response.unchangedCount()).isZero();

        assertThat(persistedApple.getName()).isEqualTo("Apple From Provider");
        assertThat(persistedApple.getOutstandingShares()).isEqualTo(15_000_000_000L);
        assertThat(persistedApple.getDividendYield()).isEqualByComparingTo("0.0044");
        assertThat(appleListing.getTicker()).isEqualTo("AAPL");
        assertThat(appleListing.getName()).isEqualTo("Apple From Provider");
        assertThat(appleListing.getStockExchange().getExchangeMICCode()).isEqualTo("XNAS");
    }

    /**
     * Persists one exchange needed by the starter stock seed flow.
     *
     * @param exchangeName full exchange name
     * @param acronym short exchange acronym
     * @param micCode exchange MIC code
     */
    private void saveExchange(String exchangeName, String acronym, String micCode) {
        StockExchange exchange = new StockExchange();
        exchange.setExchangeName(exchangeName);
        exchange.setExchangeAcronym(acronym);
        exchange.setExchangeMICCode(micCode);
        exchange.setPolity("United States");
        exchange.setCurrency("USD");
        exchange.setTimeZone("America/New_York");
        exchange.setOpenTime(LocalTime.of(9, 30));
        exchange.setCloseTime(LocalTime.of(16, 0));
        exchange.setIsActive(true);
        stockExchangeRepository.saveAndFlush(exchange);
    }
}
