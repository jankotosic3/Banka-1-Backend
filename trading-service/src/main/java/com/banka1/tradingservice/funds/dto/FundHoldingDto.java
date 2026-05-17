package com.banka1.tradingservice.funds.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Enriched response DTO za hartiju u portfoliju fonda.
 * Kombinuje FundHolding podatke sa live market podacima iz market-service-a.
 */
@Data
@Builder
public class FundHoldingDto {
    private Long id;
    private String ticker;
    private Integer quantity;
    /** Prosecna cena po kojoj je fond kupio ovu hartiju. */
    private BigDecimal avgUnitPrice;
    /** Ukupan trošak akvizicije = avgUnitPrice * quantity. */
    private BigDecimal initialMarginCost;
    /** Trenutna trzisna cena (iz market-service); null ako nije dostupna. */
    private BigDecimal price;
    /** Procenat promene cene od prethodnog zatvaranja; null ako nije dostupan. */
    private BigDecimal change;
    /** Volumen trgovanja; null ako nije dostupan. */
    private Long volume;
    /** Datum prvog unosa holdinga u fond. */
    private LocalDateTime acquisitionDate;
}
