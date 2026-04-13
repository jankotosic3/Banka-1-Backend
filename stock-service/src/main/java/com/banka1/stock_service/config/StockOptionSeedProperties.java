package com.banka1.stock_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for seeding a built-in set of stock options on application startup.
 *
 * @param enabled whether the startup seeding flow should run automatically
 */
@ConfigurationProperties(prefix = "stock.option-seed")
public record StockOptionSeedProperties(
        boolean enabled
) {
}