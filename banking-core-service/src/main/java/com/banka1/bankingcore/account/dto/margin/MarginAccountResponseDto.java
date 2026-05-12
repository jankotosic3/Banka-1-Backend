package com.banka1.bankingcore.account.dto.margin;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response DTO za GET /accounts/getMarginUser/{userId} i
 * GET /accounts/company/getMarginCompany/{companyId}.
 *
 * <p>Spec polja:
 * <pre>
 * Long companyId ili Long userId
 * String accountNumber
 * BigDecimal InitialMargin
 * BigDecimal LoanValue
 * boolean active
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MarginAccountResponseDto {

    /** Postavljeno samo ako je vlasnik korisnik. */
    private Long userId;

    /** Postavljeno samo ako je vlasnik kompanija. */
    private Long companyId;

    private String accountNumber;
    private BigDecimal initialMargin;
    private BigDecimal loanValue;
    private BigDecimal maintenanceMargin;
    private BigDecimal bankParticipation;
    private boolean active;
}
