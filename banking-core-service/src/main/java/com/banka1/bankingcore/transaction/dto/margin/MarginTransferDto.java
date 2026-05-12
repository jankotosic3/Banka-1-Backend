package com.banka1.bankingcore.transaction.dto.margin;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO za POST /transactions/addToMargin/{userId} i
 * POST /transactions/withdrawFromMargin/{userId}.
 *
 * <p>Spec (Marzni_Racuni.txt) definise {@code Double amount}. PR_14 C14.4 dodaje
 * opcioni {@code fromAccountNumber} radi explicitnog izbora tekuceg racuna kojim
 * se debituje/kredituje — ako nije zadat, banking-core uzima prvi pronadjeni RSD
 * tekuci racun klijenta. Frontend koji jos nije migriran moze nastaviti da salje
 * samo {@code amount} (auto-detect put).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarginTransferDto {

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    private BigDecimal amount;

    /**
     * Opcioni 19-cifreni broj racuna sa kojeg/na koji se prebacuju sredstva.
     * Ako je null, banking-core bira prvi RSD tekuci racun klijenta.
     */
    private String fromAccountNumber;
}
