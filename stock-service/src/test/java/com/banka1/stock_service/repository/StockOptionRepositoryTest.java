package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.OptionType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.domain.StockOption;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StockOptionRepositoryTest {

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private StockOptionRepository stockOptionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void shouldPersistStockOptionAndLoadItByTicker() {
        Stock stock = stockRepository.saveAndFlush(
                createStock("AAPL", "Apple Inc.", 15_550_061_000L, new BigDecimal("0.0044"))
        );

        StockOption stockOption = createOption(
                "AAPL250117C00210000",
                stock,
                OptionType.CALL,
                new BigDecimal("210.0000"),
                new BigDecimal("0.24500000"),
                8_250,
                LocalDate.of(2027, 1, 17)
        );
        stockOptionRepository.saveAndFlush(stockOption);

        StockOption persisted = stockOptionRepository.findByTicker("AAPL250117C00210000").orElseThrow();

        assertTrue(persisted.getId() != null);
        assertEquals(stock.getId(), persisted.getStock().getId());
        assertEquals(OptionType.CALL, persisted.getOptionType());
        assertEquals(new BigDecimal("210.0000"), persisted.getStrikePrice());
        assertEquals(new BigDecimal("0.24500000"), persisted.getImpliedVolatility());
        assertEquals(8_250, persisted.getOpenInterest());
        assertEquals(LocalDate.of(2027, 1, 17), persisted.getSettlementDate());
    }

    @Test
    void shouldEnforceUniqueTickerConstraint() {
        Stock stock = stockRepository.saveAndFlush(
                createStock("MSFT", "Microsoft Corp.", 7_433_000_000L, new BigDecimal("0.0068"))
        );

        stockOptionRepository.saveAndFlush(
                createOption(
                        "MSFT250117P00380000",
                        stock,
                        OptionType.PUT,
                        new BigDecimal("380.0000"),
                        new BigDecimal("0.19850000"),
                        4_200,
                        LocalDate.of(2027, 1, 17)
                )
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> stockOptionRepository.saveAndFlush(
                        createOption(
                                "MSFT250117P00380000",
                                stock,
                                OptionType.PUT,
                                new BigDecimal("385.0000"),
                                new BigDecimal("0.21000000"),
                                5_100,
                                LocalDate.of(2027, 1, 17)
                        )
                )
        );
    }

    @Test
    void shouldEnforceForeignKeyConstraintForUnderlyingStock() {
        Stock missingStockReference = entityManager.getReference(Stock.class, 999_999L);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> stockOptionRepository.saveAndFlush(
                        createOption(
                                "NVDA250117C00900000",
                                missingStockReference,
                                OptionType.CALL,
                                new BigDecimal("900.0000"),
                                new BigDecimal("0.33000000"),
                                9_100,
                                LocalDate.of(2027, 1, 17)
                        )
                )
        );
    }

    private Stock createStock(String ticker, String name, long outstandingShares, BigDecimal dividendYield) {
        Stock stock = new Stock();
        stock.setTicker(ticker);
        stock.setName(name);
        stock.setOutstandingShares(outstandingShares);
        stock.setDividendYield(dividendYield);
        return stock;
    }

    private StockOption createOption(
            String ticker,
            Stock stock,
            OptionType optionType,
            BigDecimal strikePrice,
            BigDecimal impliedVolatility,
            int openInterest,
            LocalDate settlementDate
    ) {
        StockOption stockOption = new StockOption();
        stockOption.setTicker(ticker);
        stockOption.setStock(stock);
        stockOption.setOptionType(optionType);
        stockOption.setStrikePrice(strikePrice);
        stockOption.setImpliedVolatility(impliedVolatility);
        stockOption.setOpenInterest(openInterest);
        stockOption.setSettlementDate(settlementDate);
        return stockOption;
    }
}
