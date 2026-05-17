package com.banka1.order.client;

import java.math.BigDecimal;

/** Client for notifying trading-service of fund-related order outcomes. */
public interface TradingServiceClient {

    /**
     * Records a security purchase into a fund's holdings.
     * Called after a BUY order for {@code INVESTMENT_FUND} executes.
     */
    void addFundHolding(Long fundId, String ticker, int quantity, BigDecimal unitPrice);

    /**
     * Mirrors a cash debit from the fund's bank account into trading-service's
     * cached fund liquidity value.
     */
    void debitFundLiquidity(Long fundId, BigDecimal amount, String reason);
}
