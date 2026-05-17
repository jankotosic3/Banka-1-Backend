package com.banka1.tradingservice.funds.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Obogaćena pozicija klijenta/banke u fondu — vraća se iz {@code GET /funds/my-positions}
 * i {@code GET /funds/bank-positions}.
 *
 * <p>Izvedena polja:
 * <ul>
 *   <li>{@code percentageOfFund} = clientTotalInvested / totalFundInvested</li>
 *   <li>{@code currentPositionValue} = percentageOfFund × fundValue</li>
 *   <li>{@code clientProfit} = currentPositionValue − totalInvested</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientFundPositionDto {
    private Long id;
    private Long clientId;
    private Long fundId;
    private String fundNaziv;
    private String fundOpis;
    private BigDecimal fundTotalValue;
    private BigDecimal totalInvested;
    private BigDecimal percentageOfFund;
    private BigDecimal currentPositionValue;
    private BigDecimal clientProfit;
    private LocalDateTime firstInvestedAt;
    private LocalDateTime lastModifiedAt;
}