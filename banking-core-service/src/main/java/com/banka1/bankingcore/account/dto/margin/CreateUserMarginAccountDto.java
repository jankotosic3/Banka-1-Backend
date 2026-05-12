package com.banka1.bankingcore.account.dto.margin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO za POST /accounts/createMarginAccount (user verzija).
 *
 * <p>Spec (Marzni_Racuni.txt):
 * <pre>
 * Long employeeId
 * Long userId
 * Double InitialMargin
 * Double MaitenanceMargin
 * Double BankParticipation
 * </pre>
 *
 * <p>employeeId se uzima iz JWT-a u kontroleru radi audita; ali ostavljamo i u DTO-u
 * radi backward kompatibilnosti sa frontend formama koje ga eksplicitno postavljaju.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserMarginAccountDto {

    @NotNull
    private Long employeeId;

    @NotNull
    private Long userId;

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
