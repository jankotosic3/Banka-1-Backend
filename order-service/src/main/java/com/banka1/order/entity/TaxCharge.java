package com.banka1.order.entity;

import com.banka1.order.entity.enums.TaxChargeStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents capital gains tax charged on a single matched buy-sell pair.
 *
 * When a user sells securities, the profit (sale price - cost basis) is subject to
 * a 15% capital gains tax. This entity tracks the tax obligation for each matched
 * buy-sell transaction pair.
 *
 * Tax Calculation:
 * <ol>
 *   <li>Match sell transaction with corresponding buy transaction(s) using FIFO</li>
 *   <li>Calculate profit: (sellPrice - buyPrice) × quantity</li>
 *   <li>Apply 15% tax rate: profit × 0.15</li>
 *   <li>Convert to RSD if transaction was in foreign currency</li>
 *   <li>Create TaxCharge record</li>
 * </ol>
 *
 * Status Lifecycle:
 * <ul>
 *   <li>PENDING: Calculated but not yet charged to user</li>
 *   <li>CHARGED: Deducted from user's account</li>
 *   <li>PAID: Settled with state/government account</li>
 * </ul>
 */
@Entity
@Table(
        name = "tax_charges",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tax_charges_sell_buy",
                columnNames = {"sell_transaction_id", "buy_transaction_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class TaxCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Reference to the SELL transaction that triggered this tax. */
    @Column(name = "sell_transaction_id", nullable = false)
    private Long sellTransactionId;

    /** Reference to the matched BUY transaction (FIFO matching). -1 means portfolio-average fallback. */
    @Column(name = "buy_transaction_id", nullable = false)
    private Long buyTransactionId;

    /** ID of the user (taxpayer) who made the sale. */
    @Column(nullable = false)
    private Long userId;

    /** ID of the security listing being sold. */
    @Column(nullable = false)
    private Long listingId;

    /** ID of the account from which tax will be deducted. */
    @Column(nullable = false)
    private Long sourceAccountId;

    /** Start of the tax period (first day of calendar month). */
    @Column(nullable = false)
    private LocalDateTime taxPeriodStart;

    /** End of the tax period (first day of next month). */
    @Column(nullable = false)
    private LocalDateTime taxPeriodEnd;

    /** Tax amount in the security's original currency. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    /** Tax amount converted to RSD. Used for settlement and reporting. */
    @Column(precision = 19, scale = 4)
    private BigDecimal taxAmountRsd;

    /** Current lifecycle status: PENDING, CHARGED, or PAID. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaxChargeStatus status;

    /** Timestamp when this tax charge record was created. */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** Timestamp when tax was charged to user's account. Null until charged. */
    private LocalDateTime chargedAt;

    /** OTC contract ID, set instead of sell/buy transaction IDs for exercised OTC contracts. */
    @Column(name = "otc_contract_id")
    private Long otcContractId;

    /**
     * JPA callback that sets the creation timestamp automatically.
     * Called before the first persist operation.
     */
    @PrePersist
    public void initializeCreatedAt() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
