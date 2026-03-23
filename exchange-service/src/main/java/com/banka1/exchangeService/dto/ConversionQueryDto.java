package com.banka1.exchangeService.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO koji mapira query parametre endpoint-a
 * {@code GET /calculate?fromCurrency=...&toCurrency=...&amount=...}.
 * Spring MVC automatski binduje vrednosti iz URL query string-a u ovo polje
 * i zatim pokrece Bean Validation anotacije definisane nad poljima.
 */
@Getter
@Setter
public class ConversionQueryDto {

    private static final String SUPPORTED_CURRENCY_REGEX = "^(?i)(RSD|EUR|CHF|USD|GBP|JPY|CAD|AUD)$";
    private static final String SUPPORTED_CURRENCY_MESSAGE =
            "Supported currencies are RSD, EUR, CHF, USD, GBP, JPY, CAD and AUD.";

    /**
     * Izvorna valuta iz koje korisnik konvertuje iznos.
     */
    @NotBlank(message = "fromCurrency is required.")
    @Pattern(regexp = SUPPORTED_CURRENCY_REGEX, message = SUPPORTED_CURRENCY_MESSAGE)
    @Schema(example = "EUR", allowableValues = {"RSD", "EUR", "CHF", "USD", "GBP", "JPY", "CAD", "AUD"})
    private String fromCurrency;

    /**
     * Ciljna valuta u koju se obracunava ekvivalent.
     */
    @NotBlank(message = "toCurrency is required.")
    @Pattern(regexp = SUPPORTED_CURRENCY_REGEX, message = SUPPORTED_CURRENCY_MESSAGE)
    @Schema(example = "USD", allowableValues = {"RSD", "EUR", "CHF", "USD", "GBP", "JPY", "CAD", "AUD"})
    private String toCurrency;

    /**
     * Iznos koji treba preracunati.
     */
    @NotNull(message = "amount is required.")
    @DecimalMin(value = "0.00000001", message = "amount must be greater than 0.")
    @Schema(example = "100.00")
    private BigDecimal amount;

    /**
     * Opcioni datum kursne liste.
     * Ako nije zadat, koristi se poslednji raspolozivi lokalni snapshot.
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    @Schema(example = "2026-03-22")
    private LocalDate date;
}
