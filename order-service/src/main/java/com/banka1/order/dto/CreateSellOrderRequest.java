package com.banka1.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Request DTO for creating a sell order.
 */
@Data
public class CreateSellOrderRequest {
    /** ID of the security listing to sell. */
    private Long listingId;

    /** Number of securities to sell. */
    private Integer quantity;

    /** Limit price for LIMIT and STOP_LIMIT orders. */
    private BigDecimal limitValue;

    /** Stop price for STOP and STOP_LIMIT orders. */
    private BigDecimal stopValue;

    /** Whether the order must be filled completely or not at all. */
    private Boolean allOrNone = false;

    /** Whether to use margin for this order. */
    private Boolean margin = false;

    /** ID of the account to credit funds to. */
    private Long accountId;
}
