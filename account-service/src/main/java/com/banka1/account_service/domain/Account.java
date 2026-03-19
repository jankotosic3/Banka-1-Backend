package com.banka1.account_service.domain;

import com.banka1.account_service.domain.enums.AccountOwnershipType;
import com.banka1.account_service.domain.enums.Status;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "account_table"
)
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "account_type", discriminatorType = DiscriminatorType.STRING)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
//todo da li staviti datum isteka na null
public abstract class Account extends BaseEntity{
    @Column(nullable = false,unique = true,updatable = false)
    private String brojRacuna;
    @Column(nullable = false)
    private String nazivRacuna;
    @Column(nullable = false)
    private Long vlasnik;
    @Column(nullable = false)
    private BigDecimal stanje= BigDecimal.valueOf(0);
    @Column(nullable = false)
    private BigDecimal raspolozivoStanje= BigDecimal.valueOf(0);
    @Column(nullable = false)
    private Long zaposlen;
    //todo mozda LocalDateTime
    @CreationTimestamp
    @Column(nullable = false,updatable = false)
    private LocalDateTime datumIVremeKreiranja;
    private LocalDate datumIsteka;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    private Currency currency;
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status=Status.ACTIVE;
    @Column(nullable = false)
    private BigDecimal dnevniLimit;
    @Column(nullable = false)
    private BigDecimal mesecniLimit;
    @Column(nullable = false)
    private BigDecimal dnevnaPotrosnja=BigDecimal.ZERO;
    @Column(nullable = false)
    private BigDecimal mesecnaPotrosnja=BigDecimal.ZERO;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;


    protected void validacija(AccountOwnershipType ownershipType) {
        if (ownershipType == null) {
            throw new IllegalStateException("Ownership type is required");
        }

        if (ownershipType == AccountOwnershipType.BUSINESS && this.getCompany() == null) {
            throw new IllegalStateException("Company is required for BUSINESS account");
        }

        if (ownershipType == AccountOwnershipType.PERSONAL && this.getCompany() != null) {
            throw new IllegalStateException("Company must be null for PERSONAL account");
        }
    }


}
