package com.banka1.stock_service.dto;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request model for listing-catalog filtering.
 *
 * <p>The filter intentionally keeps the contract simple and shared across all
 * listing catalog endpoints. Filters that do not apply to one listing type are
 * ignored by that endpoint. For example, {@code settlementDate} only affects
 * futures listings.
 */
@Getter
@Setter
public class ListingFilterRequest {

    /**
     * Case-insensitive prefix filter for exchange name, acronym, or MIC code.
     */
    private String exchange;

    /**
     * Case-insensitive partial match applied to listing ticker and listing name.
     */
    private String search;

    /**
     * Inclusive lower bound for listing price.
     */
    private BigDecimal minPrice;

    /**
     * Inclusive upper bound for listing price.
     */
    private BigDecimal maxPrice;

    /**
     * Inclusive lower bound for listing ask price.
     */
    private BigDecimal minAsk;

    /**
     * Inclusive upper bound for listing ask price.
     */
    private BigDecimal maxAsk;

    /**
     * Inclusive lower bound for listing bid price.
     */
    private BigDecimal minBid;

    /**
     * Inclusive upper bound for listing bid price.
     */
    private BigDecimal maxBid;

    /**
     * Inclusive lower bound for listing volume.
     */
    private Long minVolume;

    /**
     * Inclusive upper bound for listing volume.
     */
    private Long maxVolume;

    /**
     * Inclusive lower bound for futures settlement date.
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate settlementDateFrom;

    /**
     * Inclusive upper bound for futures settlement date.
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate settlementDateTo;
}
