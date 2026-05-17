package com.banka1.tradingservice.funds.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Pojedinacna uplata/isplata klijenta u/iz fonda (PR_04).
 *
 * <p>Spec (Celina 4.txt, ClientFundTransaction): {@code is_inflow=true} = uplata,
 * {@code is_inflow=false} = isplata. Status: PENDING (cek na saga commit), COMPLETED, FAILED.
 *
 * <p>Spec napomena: "Prilikom isplate novca, ako je zeljeni iznos pokriven likvidnim
 * sredstvima, novac se odmah prenosi. U suprotnom, vrsi se automatska likvidacija
 * dovoljnog broja hartija i klijent dobija obavestenje". Implementacija: ako pri
 * REDEEM-u {@code amount > fund.likvidnaSredstva}, status ostaje PENDING dok saga
 * orchestrator ne likvidira hartije; tada COMPLETED.
 */
@Entity
@Table(
        name = "client_fund_transactions",
        indexes = {
                @Index(name = "idx_cft_client_id",  columnList = "client_id"),
                @Index(name = "idx_cft_fund_id",    columnList = "fund_id"),
                @Index(name = "idx_cft_status",     columnList = "status")
        }
)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ClientFundTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @NotNull
    @Column(name = "fund_id", nullable = false)
    private Long fundId;

    @NotNull
    @DecimalMin(value = "0.01")
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @NotNull
    @Column(name = "is_inflow", nullable = false)
    private boolean inflow;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClientFundTransactionStatus status = ClientFundTransactionStatus.PENDING;

    @NotNull
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    /** Korisnikov tekuci racun sa kojeg ide ili na koji ide novac. */
    @Column(name = "client_account_number", nullable = false, length = 50)
    private String clientAccountNumber;

    @Column(length = 255)
    private String failureReason;
}
