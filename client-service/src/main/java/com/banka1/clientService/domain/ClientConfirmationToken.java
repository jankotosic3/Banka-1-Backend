package com.banka1.clientService.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entitet koji predstavlja token za potvrdu aktivacije naloga ili reset lozinke klijenta.
 * Token se cuva u hesiranom obliku (SHA-256) i moze imati vremenski rok vazenja.
 */
@Entity
@Table(name = "client_confirmation_token")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ClientConfirmationToken extends BaseEntity {

    /** SHA-256 hash vrednosti tokena koji je poslat korisniku. */
    @NotBlank
    @Column(nullable = false, unique = true)
    private String value;

    /** Datum i vreme isteka tokena; {@code null} znaci da token nema vremensko ogranicenje. */
    private LocalDateTime expirationDateTime;

    /** Klijent kome token pripada – veza je jedinstvena (1:1). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "klijent_id", nullable = false, unique = true)
    private Klijent klijent;

    /**
     * Kreira potvrdu za datog klijenta bez eksplicitnog isteka.
     *
     * @param value   hesirana vrednost tokena
     * @param klijent klijent kome token pripada
     */
    public ClientConfirmationToken(String value, Klijent klijent) {
        this.value = value;
        this.klijent = klijent;
    }
}
