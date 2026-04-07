package com.banka1.stock_service.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockOptionTest {

    @Test
    void shouldReturnFixedContractSizeAndDerivedMaintenanceMarginFromStockPrice() {
        StockOption stockOption = new StockOption();
        BigDecimal stockPrice = new BigDecimal("212.40");

        assertEquals(100, stockOption.getContractSize());
        assertEquals(new BigDecimal("10620.0000"), stockOption.calculateMaintenanceMargin(stockPrice));
    }

    @Test
    void shouldRejectNullStockPriceWhenCalculatingMaintenanceMargin() {
        StockOption stockOption = new StockOption();

        assertThrows(NullPointerException.class, () -> stockOption.calculateMaintenanceMargin(null));
    }
}
