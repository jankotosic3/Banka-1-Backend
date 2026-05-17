package com.banka1.tradingservice.funds.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO za investicioni fond. {@code totalValue} i {@code profit} su izvedeni
 * (racunaju se per query u servisu) — vidi spec.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestmentFundDto {
    private Long id;
    private String naziv;
    private String opis;
    private BigDecimal minimumContribution;
    private Long managerId;
    private String managerIme;
    private String managerPrezime;
    private BigDecimal likvidnaSredstva;
    private Long accountId;
    private String accountNumber;
    private LocalDate datumKreiranja;
    /** Izvedeno: likvidnaSredstva + suma vrednosti hartija. */
    private BigDecimal totalValue;
    /** Izvedeno: totalValue - sumOfClientInvestments. */
    private BigDecimal profit;
}
