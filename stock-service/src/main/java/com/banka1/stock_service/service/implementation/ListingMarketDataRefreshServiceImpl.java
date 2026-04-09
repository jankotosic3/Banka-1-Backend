package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.client.AlphaVantageClient;
import com.banka1.stock_service.domain.ForexPair;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingDailyPriceInfo;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.dto.AlphaVantageForexExchangeRateResponse;
import com.banka1.stock_service.dto.AlphaVantageQuoteResponse;
import com.banka1.stock_service.dto.ListingRefreshResponse;
import com.banka1.stock_service.repository.ForexPairRepository;
import com.banka1.stock_service.repository.ListingDailyPriceInfoRepository;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockRepository;
import com.banka1.stock_service.service.ListingMarketDataRefreshService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Default implementation of manual listing market-data refresh.
 *
 * <p>The implementation intentionally keeps the scope simpler than the full stock refresh flow:
 *
 * <ul>
 *     <li>stock listings use the latest quote endpoint</li>
 *     <li>FX listings use the latest exchange-rate endpoint</li>
 *     <li>futures listings are currently unsupported because no live provider mapping exists</li>
 * </ul>
 *
 * <p>Each refresh also upserts one {@link ListingDailyPriceInfo} row for the current trading date.
 * Because the row is updated on every intraday refresh, the last successful refresh of the day
 * naturally becomes the end-of-day snapshot.
 */
@Service
public class ListingMarketDataRefreshServiceImpl implements ListingMarketDataRefreshService {

    private static final BigDecimal ZERO_CHANGE = new BigDecimal("0.00000000");

    private final ListingRepository listingRepository;
    private final ListingDailyPriceInfoRepository listingDailyPriceInfoRepository;
    private final StockRepository stockRepository;
    private final ForexPairRepository forexPairRepository;
    private final AlphaVantageClient alphaVantageClient;
    private final Clock clock;

    /**
     * Creates the production service using the system UTC clock.
     *
     * @param listingRepository repository for listing snapshots
     * @param listingDailyPriceInfoRepository repository for listing daily snapshots
     * @param stockRepository repository for stock entities
     * @param forexPairRepository repository for FX pair entities
     * @param alphaVantageClient external market-data provider client
     */
    @Autowired
    public ListingMarketDataRefreshServiceImpl(
            ListingRepository listingRepository,
            ListingDailyPriceInfoRepository listingDailyPriceInfoRepository,
            StockRepository stockRepository,
            ForexPairRepository forexPairRepository,
            AlphaVantageClient alphaVantageClient
    ) {
        this(
                listingRepository,
                listingDailyPriceInfoRepository,
                stockRepository,
                forexPairRepository,
                alphaVantageClient,
                Clock.systemUTC()
        );
    }

    /**
     * Creates the service with an explicit clock for deterministic tests.
     *
     * @param listingRepository repository for listing snapshots
     * @param listingDailyPriceInfoRepository repository for listing daily snapshots
     * @param stockRepository repository for stock entities
     * @param forexPairRepository repository for FX pair entities
     * @param alphaVantageClient external market-data provider client
     * @param clock time source used for {@code listing.lastRefresh}
     */
    ListingMarketDataRefreshServiceImpl(
            ListingRepository listingRepository,
            ListingDailyPriceInfoRepository listingDailyPriceInfoRepository,
            StockRepository stockRepository,
            ForexPairRepository forexPairRepository,
            AlphaVantageClient alphaVantageClient,
            Clock clock
    ) {
        this.listingRepository = listingRepository;
        this.listingDailyPriceInfoRepository = listingDailyPriceInfoRepository;
        this.stockRepository = stockRepository;
        this.forexPairRepository = forexPairRepository;
        this.alphaVantageClient = alphaVantageClient;
        this.clock = clock;
    }

    /**
     * Refreshes one persisted listing and upserts its current-day daily snapshot.
     *
     * @param listingId listing identifier
     * @return refresh summary
     */
    @Override
    @Transactional
    public ListingRefreshResponse refreshListing(Long listingId) {
        Listing listing = findListing(listingId);
        LocalDateTime refreshTimestamp = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate dailySnapshotDate;

        if (listing.getListingType() == ListingType.STOCK) {
            dailySnapshotDate = refreshStockListing(listing, refreshTimestamp);
        } else if (listing.getListingType() == ListingType.FOREX) {
            dailySnapshotDate = refreshForexListing(listing, refreshTimestamp);
        } else {
            // Futures use static seed data only — there is no live market-data provider
            // for futures in this service. This is an expected, intentional limitation,
            // not a malformed request, so 422 Unprocessable Entity is more accurate than 400.
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Futures market data refresh is not supported — futures use static seed data."
            );
        }

        upsertDailySnapshot(listing, dailySnapshotDate);
        listingRepository.save(listing);

        return new ListingRefreshResponse(
                listing.getId(),
                listing.getTicker(),
                listing.getListingType(),
                dailySnapshotDate,
                refreshTimestamp
        );
    }

    private Listing findListing(Long listingId) {
        return listingRepository.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Listing with id %d was not found.".formatted(listingId)
                ));
    }

    private LocalDate refreshStockListing(Listing listing, LocalDateTime refreshTimestamp) {
        Stock stock = stockRepository.findById(listing.getSecurityId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Stock with id %d was not found for listing %d."
                                .formatted(listing.getSecurityId(), listing.getId())
                ));
        AlphaVantageQuoteResponse quoteResponse = alphaVantageClient.fetchQuote(stock.getTicker());

        listing.setTicker(stock.getTicker());
        listing.setPrice(quoteResponse.price());
        listing.setAsk(quoteResponse.ask());
        listing.setBid(quoteResponse.bid());
        listing.setChange(quoteResponse.change());
        listing.setVolume(quoteResponse.volume());
        listing.setLastRefresh(refreshTimestamp);

        return quoteResponse.latestTradingDay();
    }

    private LocalDate refreshForexListing(Listing listing, LocalDateTime refreshTimestamp) {
        ForexPair forexPair = forexPairRepository.findById(listing.getSecurityId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Forex pair with id %d was not found for listing %d."
                                .formatted(listing.getSecurityId(), listing.getId())
                ));
        CurrencyPair currencyPair = parseCurrencyPair(listing.getTicker());
        AlphaVantageForexExchangeRateResponse exchangeRateResponse = alphaVantageClient.fetchExchangeRate(
                currencyPair.baseCurrency(),
                currencyPair.quoteCurrency()
        );

        BigDecimal previousPrice = listing.getPrice();
        BigDecimal currentPrice = exchangeRateResponse.exchangeRate();

        forexPair.setExchangeRate(currentPrice);
        forexPairRepository.save(forexPair);
        listing.setPrice(currentPrice);
        listing.setAsk(currentPrice);
        listing.setBid(currentPrice);
        listing.setChange(resolveForexChange(previousPrice, currentPrice));
        listing.setVolume((long) forexPair.getContractSize());
        listing.setLastRefresh(refreshTimestamp);

        return exchangeRateResponse.lastRefreshed().toLocalDate();
    }

    private BigDecimal resolveForexChange(BigDecimal previousPrice, BigDecimal currentPrice) {
        if (previousPrice == null || previousPrice.signum() == 0) {
            return ZERO_CHANGE;
        }
        return currentPrice.subtract(previousPrice);
    }

    private void upsertDailySnapshot(Listing listing, LocalDate date) {
        ListingDailyPriceInfo dailySnapshot = listingDailyPriceInfoRepository.findByListingIdAndDate(listing.getId(), date)
                .orElseGet(() -> createDailySnapshot(listing, date));

        dailySnapshot.setPrice(listing.getPrice());
        dailySnapshot.setAsk(listing.getAsk());
        dailySnapshot.setBid(listing.getBid());
        dailySnapshot.setChange(listing.getChange());
        dailySnapshot.setVolume(listing.getVolume());

        listingDailyPriceInfoRepository.save(dailySnapshot);
    }

    private ListingDailyPriceInfo createDailySnapshot(Listing listing, LocalDate date) {
        ListingDailyPriceInfo dailySnapshot = new ListingDailyPriceInfo();
        dailySnapshot.setListing(listing);
        dailySnapshot.setDate(date);
        return dailySnapshot;
    }

    private CurrencyPair parseCurrencyPair(String ticker) {
        if (ticker == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FX listing ticker must not be null.");
        }

        String[] parts = ticker.toUpperCase(Locale.ROOT).split("/");
        if (parts.length != 2 || parts[0].length() != 3 || parts[1].length() != 3) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "FX listing ticker must use BASE/QUOTE format."
            );
        }

        return new CurrencyPair(parts[0], parts[1]);
    }

    /**
     * Parsed ordered FX pair extracted from the listing ticker.
     *
     * @param baseCurrency base currency code
     * @param quoteCurrency quote currency code
     */
    private record CurrencyPair(
            String baseCurrency,
            String quoteCurrency
    ) {
    }
}
