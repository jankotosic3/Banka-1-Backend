package com.banka1.bankingcore.transaction.domain.margin;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Audit-log entry za jednu margin transakciju (PR_03 C3.7).
 *
 * <p>Kreira se iz {@link com.banka1.bankingcore.transaction.service.margin.MarginTransactionService}
 * na svaki buy/sell/add/withdraw poziv. Spec ne zahteva eksplicitno tabelu, ali bez nje
 * "GetAllMarginTransactions" endpoint nema sta da vraca — implementacija se odlucuje
 * za audit-log pristup, koji je idiomatski u banking domen-u i potreban je za PR_07
 * (compliance/GDPR audit) ionako.
 *
 * <p>Tabela {@code margin_transactions} je odvojena od opste {@code transactions} jer
 * margin operacije imaju razlicit set polja (LoanValue delta, BankParticipation
 * snapshot u trenutku transakcije).
 */
@Entity
@Table(
        name = "margin_transactions",
        indexes = {
                @Index(name = "idx_margin_tx_account_number", columnList = "account_number"),
                @Index(name = "idx_margin_tx_occurred_at",    columnList = "occurred_at")
        }
)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class MarginTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "account_number", nullable = false, length = 16)
    private String accountNumber;

    /** Pozitivno = primanje na marzni; negativno = isplata sa marznog. */
    @NotNull
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 32)
    private MarginTransactionType transactionType;

    /** Snapshot loanValue-a u trenutku transakcije. */
    @Column(name = "loan_value_after", precision = 19, scale = 2)
    private BigDecimal loanValueAfter;

    /** Snapshot initialMargin-a posle transakcije. */
    @Column(name = "initial_margin_after", precision = 19, scale = 2)
    private BigDecimal initialMarginAfter;

    @Column(length = 255)
    private String description;

    @NotNull
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();
}
