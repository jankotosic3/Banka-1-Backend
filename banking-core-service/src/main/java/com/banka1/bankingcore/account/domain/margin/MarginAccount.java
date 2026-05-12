package com.banka1.bankingcore.account.domain.margin;

import com.banka1.bankingcore.account.domain.enums.Currency;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA natklasa za marzne racune (PR_03 C3.1).
 *
 * <p>Spec referenca: {@code Marzni_Racuni.txt} u root-u repo-a. Marzni racuni
 * (margin accounts) su poseban tip racuna koji omogucava klijentu da kupuje
 * vrednosne papire delom svojim novcem, delom pozajmicom od banke. Spec definise:
 *
 * <ul>
 *   <li>{@code initialMargin}    — pocetno stanje na racunu (klijentov novac).
 *   <li>{@code loanValue}        — koliko klijent duguje banci (na pocetku 0).
 *   <li>{@code maintenanceMargin}— minimum koji racun mora imati da bi se mogao koristiti.
 *   <li>{@code bankParticipation}— procenat (0–1) koji banka pokriva pri kupovini.
 *   <li>{@code accountNumber}    — 16-cifreni broj racuna; valuta uvek RSD.
 *   <li>{@code active}           — true ako se racun moze koristiti (false kada padne ispod
 *                                  maintenance, ponovo true kada se prebaci dovoljno preko).
 * </ul>
 *
 * <p>Konkretne podklase ({@link UserMarginAccount} i {@link CompanyMarginAccount})
 * dodaju vlasnistvo (userId odnosno companyId). Spec naglasava da klijent/kompanija
 * mogu imati <strong>najvise jedan</strong> marzni racun — enforce-ovano kroz
 * uniqueness constraint na DB nivou.
 *
 * <p>InheritanceStrategy = JOINED jer obe podklase imaju svoju ekskluzivnu kolonu
 * (userId vs companyId) i nije pozeljno da se mesaju u jednoj tabeli.
 */
@Entity
@Table(name = "margin_accounts")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "owner_kind", discriminatorType = DiscriminatorType.STRING, length = 16)
@SQLDelete(sql = "UPDATE margin_accounts SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public abstract class MarginAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Pocetno stanje na racunu — klijentov novac (RSD).
     * Spec: "InitialMargin - stanje na racunu, kolicina novca imamo".
     */
    @NotNull
    @DecimalMin(value = "0.00")
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal initialMargin;

    /**
     * Iznos koji klijent duguje banci. Pocetna vrednost je 0; raste pri buy
     * transakcijama, opada pri sell transakcijama.
     * Spec: "LoanValue - koliko smo duzni banci (na pocetku nula)".
     */
    @NotNull
    @DecimalMin(value = "0.00")
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal loanValue = BigDecimal.ZERO;

    /**
     * Minimalni iznos na racunu da bi se mogao koristiti.
     * Spec: "MaitenanceMargin - kolicina novca koju moramo imati na racunu, kako bi mogli
     * da koristimo racun".
     */
    @NotNull
    @DecimalMin(value = "0.00")
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal maintenanceMargin;

    /**
     * Procenat koji banka pokriva pri kupovini (0.0 do 1.0).
     * Spec: "BankParticipation (Double) - deo koji dobijamo od banke, izrazeno u procentima".
     * Cuvamo kao BigDecimal radi tacne BD aritmetike kod transactional pari.
     */
    @NotNull
    @DecimalMin(value = "0.00")
    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal bankParticipation;

    /**
     * 16-cifreni broj racuna. Generise se prema istom mod-11 algoritmu kao i obican
     * cekirajuci racun (vidi {@code AccountNumberGenerator}).
     */
    @NotNull
    @Pattern(regexp = "\\d{16}", message = "AccountNumber mora biti tacno 16 cifara")
    @Column(nullable = false, unique = true, length = 16)
    private String accountNumber;

    /**
     * Valuta — uvek RSD.
     * Spec: "Currency - uvek RSD".
     */
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency = Currency.RSD;

    /**
     * Da li je racun aktivan (default true).
     * Spec: "Active - boolean da li je racun dostupan za koriscenje (default je true)".
     *
     * <p>Postavlja se na false kada {@code initialMargin} padne ispod
     * {@code maintenanceMargin}; vraca se na true kada se uplatama prebaci preko.
     * (vidi {@code MarginAccountService.recalcActive}).
     */
    @Column(nullable = false)
    private boolean active = true;

    /**
     * Soft delete flag (PR_07 GDPR cleanup).
     */
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column
    private LocalDateTime updatedAt;

    /**
     * Optimistic locking — kriticno za concurrent buy/sell na istom marznom racunu.
     * Vidi PR_06 C6.5 (concurrency hardening).
     */
    @Version
    private Long version;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
