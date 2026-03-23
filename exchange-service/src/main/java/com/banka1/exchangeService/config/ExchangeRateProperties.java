package com.banka1.exchangeService.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Ovo je Spring config klasa koja mapira vrednosti iz application.properties u Java objekat.
 * Ona je glavni config objekat za ceo exchange modul.
 * Skuplja sve sto je pod exchange.rates.* u application.properties.
 *
 * @param twelveDataBaseUrl bazni URL Twelve Data servisa
 * @param twelveDataApiKey  API kljuc za pristup Twelve Data endpoint-u
 * @param marginPercentage     bankarska marza izrazena u procentima; npr. {@code 1.0} znaci 1%
 * @param commissionPercentage provizija za kalkulaciju ekvivalencije izrazena u procentima; opseg 0-1
 * @param fetchOnStartup       da li servis treba da povuce kurseve pri pokretanju
 */
@Validated
@ConfigurationProperties(prefix = "exchange.rates")
public record ExchangeRateProperties(
        @NotBlank String twelveDataBaseUrl,
        @NotBlank String twelveDataApiKey,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal marginPercentage,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal commissionPercentage,
        boolean fetchOnStartup
) {
}
