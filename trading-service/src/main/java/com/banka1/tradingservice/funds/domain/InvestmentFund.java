package com.banka1.tradingservice.funds.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Investicioni fond (PR_04).
 *
 * <p>Spec (Celina 4.txt, Entitet - investicioni fond):
 * <ul>
 *   <li>{@code naziv}, {@code opis} — informativni.
 *   <li>{@code minimumContribution} — minimalni ulog (RSD).
 *   <li>{@code managerId} — supervizor koji upravlja fondom (id employee-a).
 *   <li>{@code likvidnaSredstva} — RSD na racunu fonda.
 *   <li>{@code accountNumber} — RSD racun fonda.
 * </ul>
 * <p>{@code vrednostFonda} i {@code profit} su izvedeni — racunaju se per query
 * (likvidnaSredstva + suma vrednosti hartija fonda) — ne cuvaju u DB-u.
 */
@Entity
@Table(
        name = "investment_funds",
        indexes = {
                @Index(name = "idx_investment_funds_manager_id",     columnList = "manager_id"),
                @Index(name = "idx_investment_funds_account_number", columnList = "account_number")
        }
)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class InvestmentFund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, length = 64)
    private String naziv;

    @Column(length = 1024)
    private String opis;

    @NotNull
    @DecimalMin(value = "0.00")
    @Column(name = "minimum_contribution", nullable = false, precision = 19, scale = 2)
    private BigDecimal minimumContribution;

    @NotNull
    @Column(name = "manager_id", nullable = false)
    private Long managerId;

    @NotNull
    @DecimalMin(value = "0.00")
    @Column(name = "likvidna_sredstva", nullable = false, precision = 19, scale = 2)
    private BigDecimal likvidnaSredstva = BigDecimal.ZERO;

    @NotBlank
    @Column(name = "account_number", nullable = false, unique = true, length = 50)
    private String accountNumber;

    @NotNull
    @Column(name = "datum_kreiranja", nullable = false)
    private LocalDate datumKreiranja = LocalDate.now();

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Version
    private Long version;
}
