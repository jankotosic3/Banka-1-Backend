package com.banka1.stock_service.service;

import com.banka1.stock_service.config.ListingRefreshProperties;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.dto.ListingRefreshBatchResponse;
import com.banka1.stock_service.dto.StockExchangeStatusResponse;
import com.banka1.stock_service.repository.ListingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduled batch refresher for persisted listing snapshots.
 *
 * <p>The scheduler runs on a fixed configurable delay and refreshes only listings whose
 * market is currently considered open for their listing type. Stock listings follow the
 * existing stock-exchange status logic, while FX listings use a separate weekday-only rule
 * independent of their placeholder exchange metadata.
 *
 * <p>Unsupported listing types are skipped instead of failing the entire batch.
 * Individual provider failures are logged and counted, but the batch continues with
 * the remaining listings.
 */
@Slf4j
@Component
public class ListingMarketDataScheduler {

    private static final String SOURCE = "scheduled-listing-refresh";

    private final ListingRepository listingRepository;
    private final StockExchangeService stockExchangeService;
    private final ListingMarketDataRefreshService listingMarketDataRefreshService;
    private final ListingRefreshProperties listingRefreshProperties;
    private final Clock clock;

    /**
     * Delay in milliseconds between consecutive API calls in the scheduler loop.
     * Defaults to 12 000 ms (12 seconds), which is safe for the Alpha Vantage free-tier
     * limit of 5 calls per minute. Set to 0 to disable throttling (e.g. in integration tests).
     */
    @Value("${stock.alpha-vantage.request-delay-ms:12000}")
    private long requestDelayMs;

    /**
     * Creates the production scheduler using the system UTC clock.
     *
     * @param listingRepository repository for persisted listings
     * @param stockExchangeService service for stock-exchange runtime status
     * @param listingMarketDataRefreshService listing refresh use case
     * @param listingRefreshProperties scheduler configuration properties
     */
    @Autowired
    public ListingMarketDataScheduler(
            ListingRepository listingRepository,
            StockExchangeService stockExchangeService,
            ListingMarketDataRefreshService listingMarketDataRefreshService,
            ListingRefreshProperties listingRefreshProperties
    ) {
        this(
                listingRepository,
                stockExchangeService,
                listingMarketDataRefreshService,
                listingRefreshProperties,
                Clock.systemUTC()
        );
    }

    /**
     * Creates the scheduler with an explicit clock for deterministic tests.
     *
     * @param listingRepository repository for persisted listings
     * @param stockExchangeService service for stock-exchange runtime status
     * @param listingMarketDataRefreshService listing refresh use case
     * @param listingRefreshProperties scheduler configuration properties
     * @param clock time source used for FX refresh-window checks
     */
    ListingMarketDataScheduler(
            ListingRepository listingRepository,
            StockExchangeService stockExchangeService,
            ListingMarketDataRefreshService listingMarketDataRefreshService,
            ListingRefreshProperties listingRefreshProperties,
            Clock clock
    ) {
        this.listingRepository = listingRepository;
        this.stockExchangeService = stockExchangeService;
        this.listingMarketDataRefreshService = listingMarketDataRefreshService;
        this.listingRefreshProperties = listingRefreshProperties;
        this.clock = clock;
    }

    /**
     * Logs a one-time warning at startup to make it explicit that futures listings use static
     * seed data and will not be refreshed by the scheduler or the manual refresh endpoint.
     */
    @PostConstruct
    public void warnFuturesStaticData() {
        log.warn(
                "Futures listings use static CSV seed data and will NOT be refreshed by the scheduler. "
                        + "Futures market data refresh is intentionally unsupported — "
                        + "update the seed CSV and re-deploy to change futures data."
        );
    }

    /**
     * Runs one scheduled refresh pass using the configured fixed delay.
     */
    @Scheduled(fixedDelayString = "${stock.listing-refresh.interval-ms:900000}")
    public void runScheduledRefresh() {
        if (!listingRefreshProperties.enabled()) {
            return;
        }

        ListingRefreshBatchResponse response = refreshOpenListings();
        log.info(
                "Scheduled listing refresh completed. processedListings={}, refreshedCount={}, "
                        + "skippedClosedCount={}, skippedStaticDataCount={}, failedCount={}",
                response.processedListings(),
                response.refreshedCount(),
                response.skippedClosedCount(),
                response.skippedUnsupportedCount(),
                response.failedCount()
        );
    }

    /**
     * Refreshes all persisted listings whose exchange is currently open.
     *
     * <p>The exchange-open check is evaluated once per exchange and reused for every listing quoted
     * on that exchange during the current batch pass.
     *
     * @return batch refresh summary used by logs and tests
     */
    public ListingRefreshBatchResponse refreshOpenListings() {
        List<Listing> listings = listingRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        Map<Long, Boolean> exchangeOpenById = new HashMap<>();
        boolean forexMarketOpen = isForexMarketOpen();

        int refreshedCount = 0;
        int skippedClosedCount = 0;
        int skippedUnsupportedCount = 0;
        int failedCount = 0;

        for (Listing listing : listings) {
            if (!isRefreshWindowOpen(listing, exchangeOpenById, forexMarketOpen)) {
                skippedClosedCount++;
                continue;
            }

            if (!supportsRefresh(listing.getListingType())) {
                skippedUnsupportedCount++;
                continue;
            }

            try {
                listingMarketDataRefreshService.refreshListing(listing.getId());
                refreshedCount++;
            } catch (ResponseStatusException exception) {
                failedCount++;
                log.warn(
                        "Failed to refresh listing id={} ticker={} because {}",
                        listing.getId(),
                        listing.getTicker(),
                        exception.getReason()
                );
            }

            // Throttle API calls to stay within the Alpha Vantage free-tier rate limit
            // (5 calls/minute). The delay is applied after every provider-backed listing
            // regardless of success or failure. Configurable via
            // stock.alpha-vantage.request-delay-ms; set to 0 to disable.
            if (requestDelayMs > 0) {
                try {
                    Thread.sleep(requestDelayMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    log.warn("Scheduler throttle sleep interrupted — aborting batch.");
                    break;
                }
            }
        }

        return new ListingRefreshBatchResponse(
                SOURCE,
                listings.size(),
                refreshedCount,
                skippedClosedCount,
                skippedUnsupportedCount,
                failedCount
        );
    }

    /**
     * Determines whether one listing should be refreshed right now based on its market type.
     *
     * @param listing listing under evaluation
     * @param exchangeOpenById exchange-open cache for exchange-traded listings
     * @param forexMarketOpen precomputed FX market-open flag for the current batch
     * @return {@code true} when the listing should be refreshed now
     */
    private boolean isRefreshWindowOpen(
            Listing listing,
            Map<Long, Boolean> exchangeOpenById,
            boolean forexMarketOpen
    ) {
        return switch (listing.getListingType()) {
            case FOREX -> forexMarketOpen;
            default -> exchangeOpenById.computeIfAbsent(
                    listing.getStockExchange().getId(),
                    this::isExchangeOpen
            );
        };
    }

    /**
     * Determines whether the exchange of one listing should be treated as open.
     *
     * @param stockExchangeId exchange identifier
     * @return {@code true} when listings on that exchange should be refreshed
     */
    private boolean isExchangeOpen(Long stockExchangeId) {
        StockExchangeStatusResponse status = stockExchangeService.getStockExchangeStatus(stockExchangeId);
        return status.open();
    }

    /**
     * Determines whether the scheduler should refresh FX listings at the current UTC date.
     *
     * <p>This intentionally decouples FX refresh behavior from placeholder stock-exchange metadata.
     * The current approximation treats FX as open 24 hours on UTC weekdays and closed on weekends.
     *
     * @return {@code true} from Monday through Friday in UTC
     */
    private boolean isForexMarketOpen() {
        DayOfWeek dayOfWeek = LocalDate.now(clock).getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
    }

    /**
     * Returns whether the current scheduler implementation supports refreshing one listing type.
     *
     * <p>FUTURES deliberately returns {@code false} because there is no live market-data provider
     * mapping for futures contracts in this service. Futures data is seeded once at startup from a
     * static CSV file and is never updated at runtime. This is an intentional design decision, not
     * a gap to be silently skipped — see {@link #warnFuturesStaticData()} for the startup notice.
     *
     * @param listingType listing category
     * @return {@code true} for provider-backed listing categories (STOCK and FOREX only)
     */
    private boolean supportsRefresh(ListingType listingType) {
        return listingType == ListingType.STOCK || listingType == ListingType.FOREX;
    }
}
