package com.banka1.stock_service.dto;

/**
 * Lightweight runtime status payload consumed by service-to-service callers
 * (e.g. order-service) that only need open/closed/after-hours flags.
 */
public record ExchangeRuntimeStatusResponse(
        boolean open,
        boolean afterHours,
        boolean closed
) {
}
