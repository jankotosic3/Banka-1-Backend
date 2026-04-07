package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.StockOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and querying {@link StockOption} entities.
 *
 * <p>Options are attached to one underlying stock, so the repository supports both
 * direct lookup by option ticker and grouped loading by stock id.
 */
public interface StockOptionRepository extends JpaRepository<StockOption, Long> {

    /**
     * Finds one stock option by its unique ticker.
     *
     * @param ticker option ticker
     * @return matching option if present
     */
    Optional<StockOption> findByTicker(String ticker);

    /**
     * Loads all options attached to one underlying stock ordered for deterministic display.
     *
     * @param stockId underlying stock id
     * @return options for the provided stock
     */
    List<StockOption> findAllByStockIdOrderBySettlementDateAscStrikePriceAsc(Long stockId);
}
