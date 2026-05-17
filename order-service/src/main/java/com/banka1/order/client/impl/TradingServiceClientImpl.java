package com.banka1.order.client.impl;

import com.banka1.order.client.TradingServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Component
@Profile("!local")
@RequiredArgsConstructor
@Slf4j
public class TradingServiceClientImpl implements TradingServiceClient {

    private final RestClient tradingRestClient;

    @Override
    public void addFundHolding(Long fundId, String ticker, int quantity, BigDecimal unitPrice) {
        log.info("addFundHolding: fundId={} ticker={} qty={} price={}", fundId, ticker, quantity, unitPrice);
        tradingRestClient.post()
                .uri("/funds/internal/{fundId}/holdings/add", fundId)
                .body(new AddHoldingRequest(ticker, quantity, unitPrice))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void debitFundLiquidity(Long fundId, BigDecimal amount, String reason) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        log.info("debitFundLiquidity: fundId={} amount={} reason={}", fundId, amount, reason);
        tradingRestClient.post()
                .uri("/funds/internal/{fundId}/liquidity/debit", fundId)
                .body(new LiquidityDebitRequest(amount, reason))
                .retrieve()
                .toBodilessEntity();
    }

    record AddHoldingRequest(String ticker, int quantity, BigDecimal unitPrice) {}

    record LiquidityDebitRequest(BigDecimal amount, String reason) {}
}
