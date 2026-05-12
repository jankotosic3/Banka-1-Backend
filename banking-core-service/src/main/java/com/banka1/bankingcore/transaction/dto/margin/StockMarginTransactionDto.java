package com.banka1.bankingcore.transaction.dto.margin;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO za POST /transactions/stockBuyMarginTransaction i
 * POST /transactions/stockSellMarginTransaction.
 *
 * <p>Spec (Marzni_Racuni.txt):
 * <pre>
 * Long userId;
 * Long companyId;
 * Double amount; //ukupna vrednost transakcije
 *
 * Jedna od vrednosti userId i companyId ce sigurno biti null
 * </pre>
 *
 * <p>Validacija "tacno jedan od userId/companyId mora biti postavljen" je u servisu,
 * ne u DTO-u, jer JSR-303 ne pokriva mutually-exclusive-required.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockMarginTransactionDto {

    private Long userId;
    private Long companyId;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    private BigDecimal amount;
}
