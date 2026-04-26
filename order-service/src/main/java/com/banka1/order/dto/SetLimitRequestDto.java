package com.banka1.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request DTO for the set-limit endpoint.
 * The limit value must be a non-null, non-negative amount expressed in RSD.
 */
@Data
public class SetLimitRequestDto {

    /** New daily trading limit in RSD. Must be greater than zero. */
    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal limit;
}
