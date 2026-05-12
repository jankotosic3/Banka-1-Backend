package com.banka1.bankingcore.transaction.domain.margin;

/**
 * Tip margin transakcije (PR_03 C3.7).
 */
public enum MarginTransactionType {
    /** Buy on margin — pozajmica od banke + skidanje sa initialMargin-a. */
    STOCK_BUY,
    /** Sell on margin — vracanje banci + dopuna initialMargin-a. */
    STOCK_SELL,
    /** Uplata sa tekuceg na marzni. */
    ADD_TO_MARGIN,
    /** Isplata sa marznog na tekuci. */
    WITHDRAW_FROM_MARGIN
}
