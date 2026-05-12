package com.banka1.bankingcore.account.dto.margin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO za POST /accounts/company/createMarginAccount.
 * Spec (Marzni_Racuni.txt): identicno sa user verzijom, samo {@code companyId} umesto {@code userId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateCompanyMarginAccountDto {

    @NotNull
    private Long employeeId;

    @NotNull
    private Long companyId;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    private BigDecimal initialMargin;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    private BigDecimal maintenanceMargin;

    @NotNull
    @DecimalMin(value = "0.0000")
    @DecimalMax(value = "1.0000")
    private BigDecimal bankParticipation;
}
