package com.banka1.bankingcore.transaction.dto.margin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Jedna stavka u istoriji transakcija marznog racuna (PR_03 C3.7).
 * Spec: "/getAllMarginTransactions/{accountNumber} ce vracati sve transakcije
 * sa brojem marznog racuna; treba da vrati i placanja i primanja novca".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarginTransactionHistoryItemDto {

    private Long id;
    private String accountNumber;
    /** Pozitivno = primanje (sell margin, addToMargin), negativno = placanje (buy margin, withdraw). */
    private BigDecimal amount;
    /** STOCK_BUY | STOCK_SELL | ADD_TO_MARGIN | WITHDRAW_FROM_MARGIN */
    private String transactionType;
    private LocalDateTime occurredAt;
    private String description;
}
