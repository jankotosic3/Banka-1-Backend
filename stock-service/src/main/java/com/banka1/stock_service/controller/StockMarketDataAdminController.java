package com.banka1.stock_service.controller;

import com.banka1.stock_service.dto.StockMarketDataRefreshResponse;
import com.banka1.stock_service.service.StockMarketDataRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administrative endpoints for manually refreshing stock market data from the external provider.
 */
@RestController
@RequestMapping("/admin/stocks")
@RequiredArgsConstructor
public class StockMarketDataAdminController {

    private final StockMarketDataRefreshService stockMarketDataRefreshService;

    /**
     * Triggers a one-shot refresh for one stock ticker.
     *
     * @param ticker stock ticker to refresh
     * @return summary of the completed refresh operation
     */
    @Operation(summary = "Refresh stock market data by ticker")
    @PostMapping("/{ticker}/refresh-market-data")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public StockMarketDataRefreshResponse refreshStockMarketData(@PathVariable String ticker) {
        return stockMarketDataRefreshService.refreshStock(ticker);
    }

    /**
     * Triggers a refresh for all persisted stock tickers.
     *
     * @return one result entry per stock, in the order they were processed
     */
    @PostMapping("/refresh-all")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public List<StockMarketDataRefreshResponse> refreshAllStocks() {
        return stockMarketDataRefreshService.refreshAllStocks();
    }
}
