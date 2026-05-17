package com.banka1.order.entity.enums;

/** Indicates who/what a BUY order is purchasing securities for. */
public enum PurchaseFor {
    /** Securities go to the bank's own portfolio (actuary/supervisor buys for the bank). */
    BANK,
    /** Securities go to an investment fund's holdings. fundId on the order identifies the fund. */
    INVESTMENT_FUND
}
