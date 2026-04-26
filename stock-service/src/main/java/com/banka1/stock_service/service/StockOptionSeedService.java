package com.banka1.stock_service.service;

import com.banka1.stock_service.domain.OptionType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.domain.StockOption;
import com.banka1.stock_service.repository.StockOptionRepository;
import com.banka1.stock_service.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Seeds a built-in set of stock options for testing purposes.
 *
 * <p>Options are seeded for AAPL and MSFT with multiple strike prices and two
 * settlement dates each, producing both CALL and PUT contracts so that
 * in-the-money / out-of-the-money scenarios can be tested once real prices
 * are loaded by the market-data refresh flow.
 *
 * <p>The seed is idempotent — existing options (matched by ticker) are left unchanged.
 */
@Service
@RequiredArgsConstructor
public class StockOptionSeedService {

    private static final String SOURCE = "built-in starter stock options";

    // Settlement dates: next quarterly expiration cycles
    private static final LocalDate EXPIRY_JUNE   = LocalDate.of(2026, 6, 19);
    private static final LocalDate EXPIRY_SEPT   = LocalDate.of(2026, 9, 18);

    private static final List<SeededOptionRow> DEFAULT_OPTIONS = List.of(
            // AAPL — current price ~$260, strikes spread around it
            new SeededOptionRow("AAPL260619C00240000", "AAPL", OptionType.CALL, "240.00", "0.22500000", 3200, EXPIRY_JUNE),
            new SeededOptionRow("AAPL260619C00250000", "AAPL", OptionType.CALL, "250.00", "0.25000000", 4100, EXPIRY_JUNE),
            new SeededOptionRow("AAPL260619C00260000", "AAPL", OptionType.CALL, "260.00", "0.28000000", 5300, EXPIRY_JUNE),
            new SeededOptionRow("AAPL260619C00270000", "AAPL", OptionType.CALL, "270.00", "0.31000000", 2800, EXPIRY_JUNE),
            new SeededOptionRow("AAPL260619C00280000", "AAPL", OptionType.CALL, "280.00", "0.35000000", 1500, EXPIRY_JUNE),
            new SeededOptionRow("AAPL260619P00240000", "AAPL", OptionType.PUT,  "240.00", "0.23000000", 2100, EXPIRY_JUNE),
            new SeededOptionRow("AAPL260619P00250000", "AAPL", OptionType.PUT,  "250.00", "0.26000000", 3400, EXPIRY_JUNE),
            new SeededOptionRow("AAPL260619P00260000", "AAPL", OptionType.PUT,  "260.00", "0.29000000", 4700, EXPIRY_JUNE),
            new SeededOptionRow("AAPL260619P00270000", "AAPL", OptionType.PUT,  "270.00", "0.32000000", 1900, EXPIRY_JUNE),
            new SeededOptionRow("AAPL260619P00280000", "AAPL", OptionType.PUT,  "280.00", "0.36000000", 900,  EXPIRY_JUNE),
            new SeededOptionRow("AAPL260918C00240000", "AAPL", OptionType.CALL, "240.00", "0.24000000", 1800, EXPIRY_SEPT),
            new SeededOptionRow("AAPL260918C00250000", "AAPL", OptionType.CALL, "250.00", "0.27000000", 2600, EXPIRY_SEPT),
            new SeededOptionRow("AAPL260918C00260000", "AAPL", OptionType.CALL, "260.00", "0.30000000", 3100, EXPIRY_SEPT),
            new SeededOptionRow("AAPL260918C00270000", "AAPL", OptionType.CALL, "270.00", "0.33000000", 1400, EXPIRY_SEPT),
            new SeededOptionRow("AAPL260918C00280000", "AAPL", OptionType.CALL, "280.00", "0.37000000", 800,  EXPIRY_SEPT),
            new SeededOptionRow("AAPL260918P00240000", "AAPL", OptionType.PUT,  "240.00", "0.25000000", 1200, EXPIRY_SEPT),
            new SeededOptionRow("AAPL260918P00250000", "AAPL", OptionType.PUT,  "250.00", "0.28000000", 2000, EXPIRY_SEPT),
            new SeededOptionRow("AAPL260918P00260000", "AAPL", OptionType.PUT,  "260.00", "0.31000000", 2900, EXPIRY_SEPT),
            new SeededOptionRow("AAPL260918P00270000", "AAPL", OptionType.PUT,  "270.00", "0.34000000", 1100, EXPIRY_SEPT),
            new SeededOptionRow("AAPL260918P00280000", "AAPL", OptionType.PUT,  "280.00", "0.38000000", 600,  EXPIRY_SEPT),

            // MSFT — current price ~$380, strikes spread around it
            new SeededOptionRow("MSFT260619C00360000", "MSFT", OptionType.CALL, "360.00", "0.21000000", 2700, EXPIRY_JUNE),
            new SeededOptionRow("MSFT260619C00370000", "MSFT", OptionType.CALL, "370.00", "0.24000000", 3500, EXPIRY_JUNE),
            new SeededOptionRow("MSFT260619C00380000", "MSFT", OptionType.CALL, "380.00", "0.27000000", 4200, EXPIRY_JUNE),
            new SeededOptionRow("MSFT260619C00390000", "MSFT", OptionType.CALL, "390.00", "0.30000000", 2300, EXPIRY_JUNE),
            new SeededOptionRow("MSFT260619C00400000", "MSFT", OptionType.CALL, "400.00", "0.33000000", 1300, EXPIRY_JUNE),
            new SeededOptionRow("MSFT260619P00360000", "MSFT", OptionType.PUT,  "360.00", "0.22000000", 1900, EXPIRY_JUNE),
            new SeededOptionRow("MSFT260619P00370000", "MSFT", OptionType.PUT,  "370.00", "0.25000000", 3100, EXPIRY_JUNE),
            new SeededOptionRow("MSFT260619P00380000", "MSFT", OptionType.PUT,  "380.00", "0.28000000", 4400, EXPIRY_JUNE),
            new SeededOptionRow("MSFT260619P00390000", "MSFT", OptionType.PUT,  "390.00", "0.31000000", 1600, EXPIRY_JUNE),
            new SeededOptionRow("MSFT260619P00400000", "MSFT", OptionType.PUT,  "400.00", "0.34000000", 700,  EXPIRY_JUNE),
            new SeededOptionRow("MSFT260918C00360000", "MSFT", OptionType.CALL, "360.00", "0.23000000", 1500, EXPIRY_SEPT),
            new SeededOptionRow("MSFT260918C00370000", "MSFT", OptionType.CALL, "370.00", "0.26000000", 2200, EXPIRY_SEPT),
            new SeededOptionRow("MSFT260918C00380000", "MSFT", OptionType.CALL, "380.00", "0.29000000", 2800, EXPIRY_SEPT),
            new SeededOptionRow("MSFT260918C00390000", "MSFT", OptionType.CALL, "390.00", "0.32000000", 1200, EXPIRY_SEPT),
            new SeededOptionRow("MSFT260918C00400000", "MSFT", OptionType.CALL, "400.00", "0.35000000", 650,  EXPIRY_SEPT),
            new SeededOptionRow("MSFT260918P00360000", "MSFT", OptionType.PUT,  "360.00", "0.24000000", 1000, EXPIRY_SEPT),
            new SeededOptionRow("MSFT260918P00370000", "MSFT", OptionType.PUT,  "370.00", "0.27000000", 1700, EXPIRY_SEPT),
            new SeededOptionRow("MSFT260918P00380000", "MSFT", OptionType.PUT,  "380.00", "0.30000000", 2500, EXPIRY_SEPT),
            new SeededOptionRow("MSFT260918P00390000", "MSFT", OptionType.PUT,  "390.00", "0.33000000", 950,  EXPIRY_SEPT),
            new SeededOptionRow("MSFT260918P00400000", "MSFT", OptionType.PUT,  "400.00", "0.36000000", 480,  EXPIRY_SEPT)
    );

    private final StockRepository stockRepository;
    private final StockOptionRepository stockOptionRepository;

    /**
     * Seeds the built-in starter options into the database.
     *
     * <p>The seed is idempotent. Options whose ticker already exists are skipped.
     *
     * @return number of options created
     */
    @Transactional
    public int seedDefaultOptions() {
        int createdCount = 0;

        for (SeededOptionRow row : DEFAULT_OPTIONS) {
            if (stockOptionRepository.findByTicker(row.ticker()).isPresent()) {
                continue;
            }

            Stock stock = stockRepository.findByTicker(row.stockTicker()).orElse(null);
            if (stock == null) {
                continue;
            }

            StockOption option = new StockOption();
            option.setTicker(row.ticker());
            option.setStock(stock);
            option.setOptionType(row.optionType());
            option.setStrikePrice(new BigDecimal(row.strikePrice()));
            option.setImpliedVolatility(new BigDecimal(row.impliedVolatility()));
            option.setOpenInterest(row.openInterest());
            option.setSettlementDate(row.settlementDate());
            stockOptionRepository.save(option);
            createdCount++;
        }

        return createdCount;
    }

    private record SeededOptionRow(
            String ticker,
            String stockTicker,
            OptionType optionType,
            String strikePrice,
            String impliedVolatility,
            int openInterest,
            LocalDate settlementDate
    ) {
    }
}