package com.banka1.transaction_service.domain;

import com.banka1.transaction_service.domain.base.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * Saved payment recipient (template) belonging to a single client.
 * Spec: Celina 2, "Primaoci placanja" - klijent moze da pregleda, kreira,
 * menja i brise svoje primaoce, koji se kasnije nude pri novom placanju.
 * Soft delete je ukljucen tako da se istorija primaoca ne gubi.
 */
@Entity
@Table(
        name = "payment_recipient",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_payment_recipient_owner_naziv",
                        columnNames = {"owner_client_id", "naziv"})
        },
        indexes = {
                @Index(name = "idx_payment_recipient_owner", columnList = "owner_client_id")
        }
)
@SQLDelete(sql = "UPDATE payment_recipient SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class PaymentRecipient extends BaseEntity {

    /** Vlasnik (klijent) kome ovaj primalac pripada. */
    @NotNull
    @Column(name = "owner_client_id", nullable = false)
    private Long ownerClientId;

    /** Naziv primaoca koji klijent vidi u listi. Mora biti jedinstven u okviru vlasnika. */
    @NotBlank
    @Column(nullable = false)
    private String naziv;

    /** Broj racuna primaoca; spec ne zahteva validnost broja u trenutku snimanja. */
    @NotBlank
    @Column(nullable = false)
    private String brojRacuna;
}
