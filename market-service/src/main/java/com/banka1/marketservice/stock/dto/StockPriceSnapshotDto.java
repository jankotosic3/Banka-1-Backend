package com.banka1.marketservice.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Snapshot trenutne cene jednog stock-a (PR_12 C12.1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockPriceSnapshotDto {
    private String ticker;
    private BigDecimal currentPrice;
    private BigDecimal openPrice;
    private BigDecimal previousClose;
    /** Procenat promene od previous close. */
    private BigDecimal changePercent;
    private Long volume;
    /** Valuta u kojoj je price (USD, EUR, RSD itd.). */
    private String currency;
    private Instant timestamp;
}
