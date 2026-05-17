package com.banka1.bankingcore.market.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Slf4j
@Component
@Profile("!local")
@RequiredArgsConstructor
public class MarketServiceClient {

    private final RestClient marketRestClient;

    public ConversionResponse convertCurrency(BigDecimal amount, String fromCurrency, String toCurrency) {
        log.info("[market-service] FX convert {} {} -> {}", amount, fromCurrency, toCurrency);
        return marketRestClient.get()
                .uri(u -> u.path("/calculate")
                        .queryParam("fromCurrency", fromCurrency)
                        .queryParam("toCurrency", toCurrency)
                        .queryParam("amount", amount.toPlainString())
                        .build())
                .retrieve()
                .body(ConversionResponse.class);
    }

    public ConversionResponse convertCurrencyNoCommission(BigDecimal amount, String fromCurrency, String toCurrency) {
        log.info("[market-service] FX convert no commission {} {} -> {}", amount, fromCurrency, toCurrency);
        return marketRestClient.get()
                .uri(u -> u.path("/internal/calculate/no-commission")
                        .queryParam("fromCurrency", fromCurrency)
                        .queryParam("toCurrency", toCurrency)
                        .queryParam("amount", amount.toPlainString())
                        .build())
                .retrieve()
                .body(ConversionResponse.class);
    }

    public record ConversionResponse(String fromCurrency,
                                     String toCurrency,
                                     BigDecimal fromAmount,
                                     BigDecimal toAmount,
                                     BigDecimal rate,
                                     BigDecimal commission) {}
}
