package com.banka1.stock_service.service;

import com.banka1.stock_service.dto.StockMarketDataRefreshResponse;

import java.util.List;

/**
 * Service contract for refreshing local stock market data from an external provider.
 */
public interface StockMarketDataRefreshService {

    /**
     * Refreshes one stock ticker and its related listing snapshots from the external provider.
     *
     * @param ticker stock ticker to refresh
     * @return summary of the completed refresh operation
     */
    StockMarketDataRefreshResponse refreshStock(String ticker);

    /**
     * Refreshes all persisted stock tickers sequentially.
     *
     * <p>Each ticker is attempted independently. Failures are recorded in the response
     * and do not abort the remaining tickers.
     *
     * @return one result entry per stock, in the order they were processed
     */
    List<StockMarketDataRefreshResponse> refreshAllStocks();
}
