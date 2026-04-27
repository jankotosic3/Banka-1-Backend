package com.banka1.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request DTO for creating a buy order.
 *
 * Defines the parameters required to place a new buy order for securities.
 * Supports market and conditional orders (limit, stop, stop-limit).
 * Validates that all required fields are positive and present.
 */
@Data
public class CreateBuyOrderRequest {
    /** ID of the security listing to buy. Must be positive and valid in stock-service. */
    @NotNull
    @Positive
    private Long listingId;

    /** Number of securities to buy. Must be positive. */
    @NotNull
    @Positive
    private Integer quantity;

    /** Limit price for LIMIT and STOP_LIMIT orders. Required for those types, ignored for MARKET and STOP. */
    @Positive
    private BigDecimal limitValue;

    /** Stop price for STOP and STOP_LIMIT orders. Required for those types, ignored for MARKET and LIMIT. */
    @Positive
    private BigDecimal stopValue;

    /** Whether the order must be filled completely or not at all. Defaults to false (allows partial fills). */
    private Boolean allOrNone = false;

    /** Whether to use margin (borrowed funds) for this order. Defaults to false. Requires appropriate permissions. */
    private Boolean margin = false;

    /** ID of the account to debit funds from. Must be positive and belong to the authenticated user. */
    @Positive
    private Long accountId;

    /** Optional bank account ID used only for actuary BUY funding. Must match the order currency when provided. */
    @Positive
    private Long bankAccountId;
}
