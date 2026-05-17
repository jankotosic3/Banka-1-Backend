package com.banka1.tradingservice.funds.listener;

import com.banka1.tradingservice.funds.service.InvestmentFundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Konzumira rezultate FUND_REDEEM sage (fast path — fond ima dovoljno likvidnih sredstava).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundRedeemResultListener {

    private final InvestmentFundService fundService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "trading.fund.redeem.success", durable = "true"),
            exchange = @Exchange(value = "saga.exchange", type = ExchangeTypes.TOPIC),
            key = "saga.FUND_REDEEM.STEP_1.fund.success"
    ))
    @Transactional
    public void onSuccess(Map<String, Object> event) {
        log.info("fund.redeem.success: {}", event);
        try {
            Long txId = toLong(event.get("transactionId"));
            Long clientId = toLong(event.get("clientId"));
            Long fundId = toLong(event.get("fundId"));
            BigDecimal amount = new BigDecimal(String.valueOf(event.get("amount")));
            fundService.completeRedeem(txId, amount, clientId, fundId);
        } catch (Exception ex) {
            log.error("fund.redeem.success handler error: {}", ex.toString(), ex);
        }
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "trading.fund.redeem.failure", durable = "true"),
            exchange = @Exchange(value = "saga.exchange", type = ExchangeTypes.TOPIC),
            key = "saga.FUND_REDEEM.STEP_1.fund.failure"
    ))
    @Transactional
    public void onFailure(Map<String, Object> event) {
        log.info("fund.redeem.failure: {}", event);
        try {
            Long txId = toLong(event.get("transactionId"));
            String reason = event.getOrDefault("failureReason", "unknown").toString();
            fundService.failTransaction(txId, reason);
        } catch (Exception ex) {
            log.error("fund.redeem.failure handler error: {}", ex.toString(), ex);
        }
    }

    private Long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(o));
    }
}