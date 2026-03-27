package com.banka1.account_service.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * DTO za zahtev ažuriranja dnevnog i mesečnog limita trošenja na računu.
 * <p>
 * Omogućava vlasnicima računa da promene svoje dnevne i mesečne limite trošenja (spending limit).
 * Zahteva verifikaciju preko mobilne aplikacije i proslađivanje verifikacijskog koda.
 * Limiteri štite račun od neovlašćenih ili slučajnih transactions.
 * <p>
 * Validacija:
 * <ul>
 *   <li>Dnevni limit mora biti > 0</li>
 *   <li>Mesečni limit mora biti > 0</li>
 *   <li>Dnevni limit mora biti <= mesečni limit</li>
 *   <li>Verifikacijski kod mora biti popunjen i validan</li>
 *   <li>Verifikacijska sesija mora biti validna i aktivna</li>
 * </ul>
 * <p>
 * Primer: Postavi dnevni limit na 500.00 i mesečni na 5000.00 sa verifikacionim kodom.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class EditAccountLimitDto {
    /**
     * Novi dnevni limit trosenja.
     * <p>
     * Mora biti manji ili jednak od mesečnog limitna i veći od 0.
     */
    @NotNull(message = "Unesi dnevni limit racuna")
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal dailyLimit;

    /**
     * Novi mesečni limit trosenja.
     * <p>
     * Mora biti veći ili jednak od dnevnog limitna.
     */
    @NotNull(message = "Unesi mesecni limit racuna")
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal monthlyLimit;

    /**
     * ID sesije verifikacije koja je iniciјalna na mobilnoj aplikaciji.
     */
    @NotNull(message = "Unesi verification session ID")
    private Long verificationSessionId;
}
