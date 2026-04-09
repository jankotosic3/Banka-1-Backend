package com.banka1.stock_service.service;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.dto.StockTickerSeedResponse;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockExchangeRepository;
import com.banka1.stock_service.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Seeds a built-in set of starter stock tickers required by the stock refresh flow.
 *
 * <p>The service intentionally keeps the implementation simple:
 *
 * <ul>
 *     <li>it works with a fixed in-memory list of starter tickers</li>
 *     <li>it creates missing {@link Stock} rows</li>
 *     <li>it creates missing {@link Listing} rows linked to those stocks</li>
 *     <li>it does not create duplicates on repeated runs</li>
 * </ul>
 *
 * <p>The starter rows use placeholder fundamentals and placeholder market snapshot values.
 * Those values are sufficient for database integrity and are expected to be replaced later by
 * the dedicated stock market-data refresh use case.
 */
@Service
@RequiredArgsConstructor
public class StockTickerSeedService {

    private static final String SOURCE = "built-in starter stock tickers";
    private static final String DEFAULT_STOCK_EXCHANGE_MIC = "XNAS";
    private static final BigDecimal ZERO_PRICE = new BigDecimal("0.00000000");
    private static final BigDecimal ZERO_DIVIDEND_YIELD = new BigDecimal("0.0000");
    private static final long ZERO_OUTSTANDING_SHARES = 0L;
    private static final long ZERO_VOLUME = 0L;
    private static final List<SeededStockRow> DEFAULT_STOCKS = List.of(
            new SeededStockRow("AAPL", "Apple Inc."),
            new SeededStockRow("MSFT", "Microsoft Corporation"),
            new SeededStockRow("GOOGL", "Alphabet Inc. Class A"),
            new SeededStockRow("AMZN", "Amazon.com, Inc."),
            new SeededStockRow("TSLA", "Tesla, Inc.")
    );

    private final StockRepository stockRepository;
    private final ListingRepository listingRepository;
    private final StockExchangeRepository stockExchangeRepository;

    /**
     * Seeds the built-in starter tickers into the database.
     *
     * <p>Each predefined ticker guarantees the presence of:
     *
     * <ul>
     *     <li>one {@link Stock} entity keyed by ticker</li>
     *     <li>one linked {@link Listing} entity with {@link ListingType#STOCK}</li>
     * </ul>
     *
     * <p>The seed is idempotent. If both required rows already exist for one ticker,
     * that ticker is counted as unchanged.
     *
     * @return seed summary
     */
    @Transactional
    public StockTickerSeedResponse seedDefaultTickers() {
        StockExchange nasdaq = stockExchangeRepository.findByExchangeMICCode(DEFAULT_STOCK_EXCHANGE_MIC)
                .orElseThrow(() -> new IllegalStateException(
                        "Stock exchange %s must exist before stock ticker seeding runs."
                                .formatted(DEFAULT_STOCK_EXCHANGE_MIC)
                ));

        int createdCount = 0;
        int unchangedCount = 0;

        for (SeededStockRow row : DEFAULT_STOCKS) {
            boolean rowCreated = false;

            Stock stock = stockRepository.findByTicker(row.ticker()).orElse(null);
            if (stock == null) {
                stock = stockRepository.saveAndFlush(createStock(row));
                rowCreated = true;
            }

            Listing listing = listingRepository.findByListingTypeAndSecurityId(ListingType.STOCK, stock.getId())
                    .orElse(null);
            if (listing == null) {
                listingRepository.save(createListing(stock, nasdaq));
                rowCreated = true;
            }

            if (rowCreated) {
                createdCount++;
            } else {
                unchangedCount++;
            }
        }

        return new StockTickerSeedResponse(
                SOURCE,
                DEFAULT_STOCKS.size(),
                createdCount,
                0,
                unchangedCount
        );
    }

    /**
     * Creates a placeholder stock entity for one starter ticker.
     *
     * @param row predefined stock seed row
     * @return new stock entity
     */
    private Stock createStock(SeededStockRow row) {
        Stock stock = new Stock();
        stock.setTicker(row.ticker());
        stock.setName(row.name());
        stock.setOutstandingShares(ZERO_OUTSTANDING_SHARES);
        stock.setDividendYield(ZERO_DIVIDEND_YIELD);
        return stock;
    }

    /**
     * Creates a placeholder listing row linked to an already persisted stock.
     *
     * @param stock persisted stock entity
     * @param stockExchange exchange used for the starter listing
     * @return new listing entity
     */
    private Listing createListing(Stock stock, StockExchange stockExchange) {
        Listing listing = new Listing();
        listing.setSecurityId(stock.getId());
        listing.setListingType(ListingType.STOCK);
        listing.setStockExchange(stockExchange);
        listing.setTicker(stock.getTicker());
        listing.setName(stock.getName());
        listing.setLastRefresh(LocalDateTime.now());
        listing.setPrice(ZERO_PRICE);
        listing.setAsk(ZERO_PRICE);
        listing.setBid(ZERO_PRICE);
        listing.setChange(ZERO_PRICE);
        listing.setVolume(ZERO_VOLUME);
        return listing;
    }

    /**
     * Immutable description of one built-in seed stock row.
     *
     * @param ticker unique stock ticker
     * @param name display name
     */
    private record SeededStockRow(
            String ticker,
            String name
    ) {
    }
}
