package com.banka1.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class TaxTrackingRowResponse {
    private String firstName;
    private String lastName;
    private String userType;
    private BigDecimal taxDebtRsd;
    private LocalDateTime lastTaxCalculationDate;
    private BigDecimal currentMonthTaxRsd;
    private BigDecimal totalPaidTaxRsd;
    private String status;
}
