package com.banka1.stock_service.domain;

/**
 * Direction of a stock option contract.
 *
 * <p>The current domain model supports the two standard vanilla option types
 * used in the project specification.
 */
public enum OptionType {

    /**
     * Call option that gives exposure to upward moves of the underlying stock.
     */
    CALL,

    /**
     * Put option that gives exposure to downward moves of the underlying stock.
     */
    PUT
}
