package com.banka1.bankingcore.interbank.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Interbank rezervacija sredstava — drzi 2PC stanje izmedju domaceg
 * banking-core-a i interbank-service-a (PR_32 Phase 11, Tim 2 §4.6).
 *
 * <p>Status flow:
 * <ul>
 *   <li>{@code HELD} — interbank-service je rezervisao sredstva preko
 *       {@code POST /internal/interbank/reserve-monas}. {@code raspolozivoStanje}
 *       je smanjen, ali {@code stanje} (full balance) jos uvek nije.</li>
 *   <li>{@code COMMITTED} — interbank-service je commit-ovao rezervaciju
 *       posle uspesnog 2PC commit faze (drugi node je acknowledged). Sad i
 *       {@code stanje} pada.</li>
 *   <li>{@code RELEASED} — rezervacija je oslobodjena (drugi node je
 *       odustao ili je doslo do timeout-a). {@code raspolozivoStanje} se
 *       vraca na originalnu vrednost.</li>
 * </ul>
 *
 * <p>Idempotentnost: ponovljeni commit/release za rezervaciju koja je vec
 * u terminal state-u je no-op.
 */
@Entity
@Table(name = "interbank_reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterbankReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private UUID reservationId;

    @Column(name = "transaction_id_routing", nullable = false)
    private int transactionIdRouting;

    @Column(name = "transaction_id_local", nullable = false, length = 64)
    private String transactionIdLocal;

    @Column(name = "account_number", nullable = false, length = 18)
    private String accountNumber;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(nullable = false, precision = 20, scale = 4)
    private BigDecimal amount;

    /** HELD / COMMITTED / RELEASED. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
