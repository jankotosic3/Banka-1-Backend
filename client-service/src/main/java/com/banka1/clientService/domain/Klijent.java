package com.banka1.clientService.domain;

import com.banka1.clientService.domain.enums.ClientRole;
import com.banka1.clientService.domain.enums.Pol;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * JPA entitet koji predstavlja klijenta banke.
 * Implementira soft delete – fizicko brisanje je zamenjeno postavljanjem zastavice {@code deleted = true},
 * a {@code @SQLRestriction} automatski iskljucuje obrisane redove iz svih upita.
 */
@Entity
@Table(
        name = "clients",
        indexes = {
                @Index(name = "idx_clients_ime_prezime", columnList = "ime, prezime"),
                @Index(name = "idx_clients_email", columnList = "email")
        }
)
@SQLDelete(sql = "UPDATE clients SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Klijent extends BaseEntity {

    /**
     * Ime klijenta.
     */
    @NotBlank
    @Column(nullable = false)
    private String ime;

    /**
     * Prezime klijenta.
     */
    @NotBlank
    @Column(nullable = false)
    private String prezime;

    /**
     * Datum rodjenja klijenta, cuvano kao Unix timestamp (milisekunde).
     */
    @NotNull
    @Column(name = "datum_rodjenja", nullable = false)
    private Long datumRodjenja;

    /**
     * Pol klijenta (M ili Z).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Pol pol;

    /**
     * Email adresa klijenta – mora biti jedinstvena u sistemu.
     */
    @NotBlank
    @Email
    @Column(nullable = false, unique = true)
    private String email;

    /**
     * Broj telefona klijenta, normalizovan u E.164 format pri postavljanju.
     * Svi ulazni formati se svode na isti oblik kako bi pretraga bila konzistentna:
     *   "+381641234567"   → "+381641234567"
     *   "00381641234567"  → "+381641234567"
     *   "381641234567"    → "+381641234567"
     *   "0641234567"      → "+381641234567"  (lokalni srpski format, pozivni +381)
     * Napomena: lokalni format pretpostavlja Srbiju (+381). Strani brojevi se unose sa + prefiksom.
     */
    private String brojTelefona;

    public void setBrojTelefona(String brojTelefona) {
        if (brojTelefona == null || brojTelefona.isBlank()) {
            this.brojTelefona = null;
            return;
        }
        String t = brojTelefona.strip();
        if (t.startsWith("+")) {
            this.brojTelefona = t;
        } else if (t.startsWith("00")) {
            this.brojTelefona = "+" + t.substring(2);
        } else if (t.startsWith("0")) {
            this.brojTelefona = "+381" + t.substring(1);
        } else {
            this.brojTelefona = "+" + t;
        }
    }

    /**
     * Adresa stanovanja klijenta.
     */
    private String adresa;

    /**
     * Uloga klijenta u sistemu.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClientRole role = ClientRole.CLIENT_BASIC;

    /**
     * Hesh lozinke klijenta (Argon2 ili slicno).
     */
    private String password;

    /**
     * Indikator da li je nalog klijenta aktivan (aktiviran putem email linka).
     */
    @Column(nullable = false)
    private boolean aktivan = false;

    /**
     * Aktivacioni / reset token vezan za klijenta (opcioni).
     */
    @OneToOne(mappedBy = "klijent", cascade = CascadeType.ALL, orphanRemoval = true)
    private ClientConfirmationToken confirmationToken;

    /**
     * Jedinstveni maticni broj gradjana klijenta – ne moze se menjati nakon kreiranja.
     */
    @NotBlank
    @Column(nullable = false, unique = true, length = 13)
    private String jmbg;

    @Override
    public String toString() {
        return "Klijent{" +
                "ime='" + ime + '\'' +
                ", prezime='" + prezime + '\'' +
                ", datumRodjenja=" + datumRodjenja +
                ", pol=" + pol +
                ", email='" + email + '\'' +
                ", brojTelefona='" + brojTelefona + '\'' +
                ", adresa='" + adresa + '\'' +
                ", jmbg='[PROTECTED]'" +
                '}';
    }
}
