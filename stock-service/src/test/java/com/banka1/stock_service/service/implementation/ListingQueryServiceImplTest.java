package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.domain.ForexPair;
import com.banka1.stock_service.domain.FuturesContract;
import com.banka1.stock_service.domain.Liquidity;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingDailyPriceInfo;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.OptionType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.domain.StockOption;
import com.banka1.stock_service.dto.ListingFilterRequest;
import com.banka1.stock_service.dto.ListingDetailsPeriod;
import com.banka1.stock_service.dto.ListingDetailsResponse;
import com.banka1.stock_service.dto.ListingSortField;
import com.banka1.stock_service.dto.ListingSummaryResponse;
import com.banka1.stock_service.repository.ForexPairRepository;
import com.banka1.stock_service.repository.FuturesContractRepository;
import com.banka1.stock_service.repository.ListingDailyPriceInfoRepository;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockExchangeRepository;
import com.banka1.stock_service.repository.StockRepository;
import com.banka1.stock_service.repository.StockOptionRepository;
import com.banka1.stock_service.service.ListingQueryService;
import com.banka1.stock_service.service.StockTickerSeedService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ListingQueryServiceImpl}.
 *
 * <p>The query service composes persisted listings with persisted underlying
 * security rows, so these tests verify filtering, derived margin calculations,
 * sorting, and pagination against the in-memory test database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ListingQueryServiceImplTest {

    @Autowired
    private ListingQueryService listingQueryService;

    @Autowired
    private StockTickerSeedService stockTickerSeedService;

    @Autowired
    private StockExchangeRepository stockExchangeRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private FuturesContractRepository futuresContractRepository;

    @Autowired
    private ForexPairRepository forexPairRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private ListingDailyPriceInfoRepository listingDailyPriceInfoRepository;

    @Autowired
    private StockOptionRepository stockOptionRepository;

    @Test
    void getStockListingsAppliesFiltersSortingAndPagination() {
        StockExchange xnas = saveExchange("Nasdaq", "NASDAQ", "XNAS");
        StockExchange xnys = saveExchange("New York Stock Exchange", "NYSE", "XNYS");

        saveStockListing(xnas, "AAPL", "Apple Inc.", "120.00000000", "121.00000000", "119.00000000", "3.50000000", 5_000L);
        saveStockListing(xnas, "AMZN", "Amazon.com, Inc.", "150.00000000", "151.00000000", "149.00000000", "2.00000000", 7_000L);
        saveStockListing(xnys, "MSFT", "Microsoft Corporation", "300.00000000", "301.00000000", "299.00000000", "4.00000000", 9_000L);

        ListingFilterRequest filter = new ListingFilterRequest();
        filter.setExchange("XNA");
        filter.setSearch("a");
        filter.setMinPrice(new BigDecimal("100.00000000"));
        filter.setMaxBid(new BigDecimal("200.00000000"));

        Page<ListingSummaryResponse> response = listingQueryService.getStockListings(
                filter,
                0,
                1,
                ListingSortField.PRICE,
                Sort.Direction.DESC
        );

        assertThat(response.getTotalElements()).isEqualTo(2);
        assertThat(response.getTotalPages()).isEqualTo(2);
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().getFirst().ticker()).isEqualTo("AMZN");
        assertThat(response.getContent().getFirst().initialMarginCost()).isEqualByComparingTo("82.5000000000");
    }

    @Test
    void getFuturesListingsSupportsSettlementDateFilterAndMaintenanceMarginSort() {
        StockExchange xcme = saveExchange("Chicago Mercantile Exchange", "CME", "XCME");

        saveFuturesListing(
                xcme,
                "CRUDEOILENERGY",
                "Crude Oil Energy",
                1_000,
                "Barrel",
                LocalDate.of(2026, 6, 15),
                "80.00000000",
                "81.00000000",
                "79.00000000",
                4_000L
        );
        saveFuturesListing(
                xcme,
                "GOLDMETALS",
                "Gold Metals",
                100,
                "Kilogram",
                LocalDate.of(2026, 7, 15),
                "200.00000000",
                "201.00000000",
                "199.00000000",
                1_000L
        );

        ListingFilterRequest filteredRequest = new ListingFilterRequest();
        filteredRequest.setSettlementDateFrom(LocalDate.of(2026, 6, 15));
        filteredRequest.setSettlementDateTo(LocalDate.of(2026, 6, 15));

        Page<ListingSummaryResponse> filteredResponse = listingQueryService.getFuturesListings(
                filteredRequest,
                0,
                20,
                ListingSortField.TICKER,
                Sort.Direction.ASC
        );

        assertThat(filteredResponse.getContent()).hasSize(1);
        assertThat(filteredResponse.getContent().getFirst().ticker()).isEqualTo("CRUDEOILENERGY");
        assertThat(filteredResponse.getContent().getFirst().settlementDate()).isEqualTo(LocalDate.of(2026, 6, 15));

        Page<ListingSummaryResponse> sortedResponse = listingQueryService.getFuturesListings(
                new ListingFilterRequest(),
                0,
                20,
                ListingSortField.MAINTENANCE_MARGIN,
                Sort.Direction.DESC
        );

        assertThat(sortedResponse.getContent()).extracting(ListingSummaryResponse::ticker)
                .containsExactly("CRUDEOILENERGY", "GOLDMETALS");
    }

    @Test
    void getForexListingsReturnsDerivedInitialMarginCost() {
        StockExchange xnas = saveExchange("Nasdaq", "NASDAQ", "XNAS");

        saveForexListing(
                xnas,
                "EUR/USD",
                "EUR / USD",
                "EUR",
                "USD",
                "1.08350000",
                "1.08370000",
                "1.08330000",
                1_000L
        );

        ListingFilterRequest filter = new ListingFilterRequest();
        filter.setSearch("eur");

        Page<ListingSummaryResponse> response = listingQueryService.getForexListings(
                filter,
                0,
                20,
                ListingSortField.PRICE,
                Sort.Direction.ASC
        );

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().getFirst().ticker()).isEqualTo("EUR/USD");
        assertThat(response.getContent().getFirst().initialMarginCost()).isEqualByComparingTo("119.1850000000");
    }

    @Test
    void getListingDetailsReturnsStockSpecificDataPeriodFilteredHistoryAndGroupedOptions() {
        StockExchange xnas = saveExchange("Nasdaq", "NASDAQ", "XNAS");
        Listing listing = saveStockListing(
                xnas,
                "AAPL",
                "Apple Inc.",
                "180.00000000",
                "180.20000000",
                "179.90000000",
                "3.00000000",
                2_000L
        );

        saveDailyPriceInfo(listing, LocalDate.of(2026, 4, 1), "172.00000000", "172.10000000", "171.90000000", "1.00000000", 900L);
        saveDailyPriceInfo(listing, LocalDate.of(2026, 4, 3), "175.00000000", "175.10000000", "174.90000000", "2.00000000", 1_100L);
        saveDailyPriceInfo(listing, LocalDate.of(2026, 4, 7), "178.00000000", "178.20000000", "177.80000000", "2.00000000", 1_500L);
        saveDailyPriceInfo(listing, LocalDate.of(2026, 4, 8), "180.00000000", "180.20000000", "179.90000000", "3.00000000", 2_000L);

        saveStockOption(
                listing.getSecurityId(),
                "AAPL260515C00175000",
                OptionType.CALL,
                "175.0000",
                "0.25000000",
                2_500,
                LocalDate.of(2026, 5, 15)
        );
        saveStockOption(
                listing.getSecurityId(),
                "AAPL260515P00170000",
                OptionType.PUT,
                "170.0000",
                "0.27000000",
                1_800,
                LocalDate.of(2026, 5, 15)
        );
        saveStockOption(
                listing.getSecurityId(),
                "AAPL260619C00190000",
                OptionType.CALL,
                "190.0000",
                "0.21000000",
                1_400,
                LocalDate.of(2026, 6, 19)
        );

        ListingDetailsResponse response = listingQueryService.getListingDetails(listing.getId(), ListingDetailsPeriod.WEEK);

        assertThat(response.listingId()).isEqualTo(listing.getId());
        assertThat(response.listingType()).isEqualTo(ListingType.STOCK);
        assertThat(response.changePercent()).isEqualByComparingTo("1.6949");
        assertThat(response.dollarVolume()).isEqualByComparingTo("360000.00000000");
        assertThat(response.initialMarginCost()).isEqualByComparingTo("99.0000000000");
        assertThat(response.requestedPeriod()).isEqualTo(ListingDetailsPeriod.WEEK);
        assertThat(response.stockDetails()).isNotNull();
        assertThat(response.stockDetails().outstandingShares()).isEqualTo(1_000_000L);
        assertThat(response.futuresDetails()).isNull();
        assertThat(response.forexDetails()).isNull();
        assertThat(response.priceHistory()).extracting(item -> item.date())
                .containsExactly(
                        LocalDate.of(2026, 4, 3),
                        LocalDate.of(2026, 4, 7),
                        LocalDate.of(2026, 4, 8)
                );
        assertThat(response.priceHistory().getLast().changePercent()).isEqualByComparingTo("1.6949");
        assertThat(response.priceHistory().getLast().dollarVolume()).isEqualByComparingTo("360000.00000000");
        assertThat(response.optionGroups()).hasSize(2);
        assertThat(response.optionGroups().getFirst().settlementDate()).isEqualTo(LocalDate.of(2026, 5, 15));
        assertThat(response.optionGroups().getFirst().calls()).hasSize(1);
        assertThat(response.optionGroups().getFirst().puts()).hasSize(1);
        assertThat(response.optionGroups().getFirst().calls().getFirst().inTheMoney()).isTrue();
        assertThat(response.optionGroups().getFirst().puts().getFirst().inTheMoney()).isFalse();
        assertThat(response.optionGroups().get(1).calls().getFirst().inTheMoney()).isFalse();
    }

    @Test
    void getListingDetailsReturnsFuturesSpecificFields() {
        StockExchange xcme = saveExchange("Chicago Mercantile Exchange", "CME", "XCME");
        Listing listing = saveFuturesListing(
                xcme,
                "CRUDEOILENERGY",
                "Crude Oil Energy",
                1_000,
                "Barrel",
                LocalDate.of(2026, 6, 15),
                "80.00000000",
                "81.00000000",
                "79.00000000",
                4_000L
        );
        saveDailyPriceInfo(listing, LocalDate.of(2026, 4, 8), "80.00000000", "81.00000000", "79.00000000", "1.00000000", 4_000L);

        ListingDetailsResponse response = listingQueryService.getListingDetails(listing.getId(), ListingDetailsPeriod.ALL);

        assertThat(response.listingType()).isEqualTo(ListingType.FUTURES);
        assertThat(response.futuresDetails()).isNotNull();
        assertThat(response.futuresDetails().contractSize()).isEqualTo(1_000);
        assertThat(response.futuresDetails().contractUnit()).isEqualTo("Barrel");
        assertThat(response.futuresDetails().settlementDate()).isEqualTo(LocalDate.of(2026, 6, 15));
        assertThat(response.initialMarginCost()).isEqualByComparingTo("8800.0000000000");
        assertThat(response.optionGroups()).isEmpty();
    }

    @Test
    void getListingDetailsReturnsForexSpecificFields() {
        StockExchange xnas = saveExchange("Nasdaq", "NASDAQ", "XNAS");
        Listing listing = saveForexListing(
                xnas,
                "EUR/USD",
                "EUR / USD",
                "EUR",
                "USD",
                "1.08350000",
                "1.08370000",
                "1.08330000",
                1_000L
        );
        saveDailyPriceInfo(listing, LocalDate.of(2026, 4, 8), "1.08350000", "1.08370000", "1.08330000", "0.00050000", 1_000L);

        ListingDetailsResponse response = listingQueryService.getListingDetails(listing.getId(), ListingDetailsPeriod.DAY);

        assertThat(response.listingType()).isEqualTo(ListingType.FOREX);
        assertThat(response.forexDetails()).isNotNull();
        assertThat(response.forexDetails().baseCurrency()).isEqualTo("EUR");
        assertThat(response.forexDetails().quoteCurrency()).isEqualTo("USD");
        assertThat(response.forexDetails().exchangeRate()).isEqualByComparingTo("1.08350000");
        assertThat(response.forexDetails().liquidity()).isEqualTo(Liquidity.HIGH);
        assertThat(response.forexDetails().contractSize()).isEqualTo(1_000);
        assertThat(response.initialMarginCost()).isEqualByComparingTo("119.1850000000");
        assertThat(response.priceHistory()).hasSize(1);
    }

    @Test
    void getListingDetailsReturnsNullChangePercentForSeededStockBeforeRefresh() {
        saveExchange("Nasdaq", "NASDAQ", "XNAS");
        saveExchange("New York Portfolio Clearing", "NYPC", "NYPC");
        saveExchange("Chicago Mercantile Exchange", "CME", "XCME");

        stockTickerSeedService.seedDefaultTickers();

        Stock apple = stockRepository.findByTicker("AAPL").orElseThrow();
        Listing seededListing = listingRepository.findByListingTypeAndSecurityId(ListingType.STOCK, apple.getId())
                .orElseThrow();

        ListingDetailsResponse response = listingQueryService.getListingDetails(seededListing.getId(), ListingDetailsPeriod.DAY);

        assertThat(response.listingId()).isEqualTo(seededListing.getId());
        assertThat(response.ticker()).isEqualTo("AAPL");
        assertThat(response.price()).isEqualByComparingTo("0.00000000");
        assertThat(response.change()).isEqualByComparingTo("0.00000000");
        assertThat(response.changePercent()).isNull();
        assertThat(response.dollarVolume()).isEqualByComparingTo("0E-8");
        assertThat(response.priceHistory()).isEmpty();
    }

    @Test
    void getListingDetailsReturnsNullChangePercentForHistoryRowsWithoutPreviousPrice() {
        StockExchange xnas = saveExchange("Nasdaq", "NASDAQ", "XNAS");
        Listing listing = saveStockListing(
                xnas,
                "AAPL",
                "Apple Inc.",
                "0.00000000",
                "0.00000000",
                "0.00000000",
                "0.00000000",
                0L
        );

        saveDailyPriceInfo(listing, LocalDate.of(2026, 4, 8), "0.00000000", "0.00000000", "0.00000000", "0.00000000", 0L);

        ListingDetailsResponse response = listingQueryService.getListingDetails(listing.getId(), ListingDetailsPeriod.DAY);

        assertThat(response.changePercent()).isNull();
        assertThat(response.priceHistory()).hasSize(1);
        assertThat(response.priceHistory().getFirst().changePercent()).isNull();
        assertThat(response.priceHistory().getFirst().dollarVolume()).isEqualByComparingTo("0E-8");
    }

    /**
     * Persists one exchange used by the listing query tests.
     *
     * @param exchangeName exchange display name
     * @param acronym exchange acronym
     * @param micCode exchange MIC code
     * @return persisted exchange
     */
    private StockExchange saveExchange(String exchangeName, String acronym, String micCode) {
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
        return stockExchangeRepository.saveAndFlush(exchange);
    }

    /**
     * Persists one stock and its linked listing.
     *
     * @param exchange quoted exchange
     * @param ticker stock ticker
     * @param name stock display name
     * @param price current price
     * @param ask current ask
     * @param bid current bid
     * @param change current change
     * @param volume current volume
     */
    private Listing saveStockListing(
            StockExchange exchange,
            String ticker,
            String name,
            String price,
            String ask,
            String bid,
            String change,
            long volume
    ) {
        Stock stock = new Stock();
        stock.setTicker(ticker);
        stock.setName(name);
        stock.setOutstandingShares(1_000_000L);
        stock.setDividendYield(new BigDecimal("0.0100"));
        stock = stockRepository.saveAndFlush(stock);

        Listing listing = createListing(exchange, ListingType.STOCK, stock.getId(), ticker, name, price, ask, bid, change, volume);
        return listingRepository.saveAndFlush(listing);
    }

    /**
     * Persists one futures contract and its linked listing.
     *
     * @param exchange quoted exchange
     * @param ticker futures ticker
     * @param name futures display name
     * @param contractSize contract size
     * @param contractUnit contract unit
     * @param settlementDate settlement date
     * @param price current price
     * @param ask current ask
     * @param bid current bid
     * @param volume current volume
     */
    private Listing saveFuturesListing(
            StockExchange exchange,
            String ticker,
            String name,
            int contractSize,
            String contractUnit,
            LocalDate settlementDate,
            String price,
            String ask,
            String bid,
            long volume
    ) {
        FuturesContract contract = new FuturesContract();
        contract.setTicker(ticker);
        contract.setName(name);
        contract.setContractSize(contractSize);
        contract.setContractUnit(contractUnit);
        contract.setSettlementDate(settlementDate);
        contract = futuresContractRepository.saveAndFlush(contract);

        Listing listing = createListing(
                exchange,
                ListingType.FUTURES,
                contract.getId(),
                ticker,
                name,
                price,
                ask,
                bid,
                "1.00000000",
                volume
        );
        return listingRepository.saveAndFlush(listing);
    }

    /**
     * Persists one FX pair and its linked listing.
     *
     * @param exchange quoted exchange
     * @param ticker FX ticker
     * @param name listing display name
     * @param baseCurrency base currency code
     * @param quoteCurrency quote currency code
     * @param rate exchange rate
     * @param ask current ask
     * @param bid current bid
     * @param volume current volume
     */
    private Listing saveForexListing(
            StockExchange exchange,
            String ticker,
            String name,
            String baseCurrency,
            String quoteCurrency,
            String rate,
            String ask,
            String bid,
            long volume
    ) {
        ForexPair pair = new ForexPair();
        pair.setTicker(ticker);
        pair.setBaseCurrency(baseCurrency);
        pair.setQuoteCurrency(quoteCurrency);
        pair.setExchangeRate(new BigDecimal(rate));
        pair.setLiquidity(Liquidity.HIGH);
        pair = forexPairRepository.saveAndFlush(pair);

        Listing listing = createListing(exchange, ListingType.FOREX, pair.getId(), ticker, name, rate, ask, bid, "0.00000000", volume);
        return listingRepository.saveAndFlush(listing);
    }

    /**
     * Persists one historical daily price row for the provided listing.
     *
     * @param listing parent listing
     * @param date trading day
     * @param price closing or reference price
     * @param ask ask price
     * @param bid bid price
     * @param change absolute change
     * @param volume traded volume
     */
    private void saveDailyPriceInfo(
            Listing listing,
            LocalDate date,
            String price,
            String ask,
            String bid,
            String change,
            long volume
    ) {
        ListingDailyPriceInfo entity = new ListingDailyPriceInfo();
        entity.setListing(listing);
        entity.setDate(date);
        entity.setPrice(new BigDecimal(price));
        entity.setAsk(new BigDecimal(ask));
        entity.setBid(new BigDecimal(bid));
        entity.setChange(new BigDecimal(change));
        entity.setVolume(volume);
        listingDailyPriceInfoRepository.saveAndFlush(entity);
    }

    /**
     * Persists one stock option for the provided underlying stock id.
     *
     * @param stockId underlying stock id
     * @param ticker option ticker
     * @param optionType option type
     * @param strikePrice strike price
     * @param impliedVolatility implied volatility
     * @param openInterest open interest
     * @param settlementDate settlement date
     */
    private void saveStockOption(
            Long stockId,
            String ticker,
            OptionType optionType,
            String strikePrice,
            String impliedVolatility,
            int openInterest,
            LocalDate settlementDate
    ) {
        StockOption option = new StockOption();
        option.setTicker(ticker);
        option.setStock(stockRepository.findById(stockId).orElseThrow());
        option.setOptionType(optionType);
        option.setStrikePrice(new BigDecimal(strikePrice));
        option.setImpliedVolatility(new BigDecimal(impliedVolatility));
        option.setOpenInterest(openInterest);
        option.setSettlementDate(settlementDate);
        stockOptionRepository.saveAndFlush(option);
    }

    /**
     * Creates one listing row used by the query tests.
     *
     * @param exchange quoted exchange
     * @param listingType listing category
     * @param securityId underlying security id
     * @param ticker listing ticker
     * @param name listing display name
     * @param price current price
     * @param ask current ask
     * @param bid current bid
     * @param change current change
     * @param volume current volume
     * @return new listing entity
     */
    private Listing createListing(
            StockExchange exchange,
            ListingType listingType,
            Long securityId,
            String ticker,
            String name,
            String price,
            String ask,
            String bid,
            String change,
            long volume
    ) {
        Listing listing = new Listing();
        listing.setSecurityId(securityId);
        listing.setListingType(listingType);
        listing.setStockExchange(exchange);
        listing.setTicker(ticker);
        listing.setName(name);
        listing.setLastRefresh(LocalDateTime.of(2026, 4, 8, 12, 0));
        listing.setPrice(new BigDecimal(price));
        listing.setAsk(new BigDecimal(ask));
        listing.setBid(new BigDecimal(bid));
        listing.setChange(new BigDecimal(change));
        listing.setVolume(volume);
        return listing;
    }
}
