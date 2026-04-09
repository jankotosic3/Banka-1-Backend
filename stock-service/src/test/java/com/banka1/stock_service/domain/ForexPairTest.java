package com.banka1.stock_service.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ForexPairTest {

    @Test
    void shouldReturnFixedContractSizeAndDerivedValuesFromPrice() {
        ForexPair forexPair = new ForexPair();
        BigDecimal price = new BigDecimal("1.08350");

        assertEquals(1_000, forexPair.getContractSize());
        assertEquals(new BigDecimal("1083.50000"), forexPair.calculateNominalValue(price));
        assertEquals(new BigDecimal("108.3500000"), forexPair.calculateMaintenanceMargin(price));
    }

    @Test
    void shouldRejectNullPriceWhenCalculatingDerivedValues() {
        ForexPair forexPair = new ForexPair();

        assertThrows(NullPointerException.class, () -> forexPair.calculateNominalValue(null));
        assertThrows(NullPointerException.class, () -> forexPair.calculateMaintenanceMargin(null));
    }
}
