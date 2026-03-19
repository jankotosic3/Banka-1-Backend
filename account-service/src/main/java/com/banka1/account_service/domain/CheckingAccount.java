package com.banka1.account_service.domain;

import com.banka1.account_service.domain.enums.AccountConcrete;
import com.banka1.account_service.domain.enums.CurrencyCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@NoArgsConstructor
@Getter
@Setter
@Entity
@DiscriminatorValue("CHECKING")

public class CheckingAccount extends Account{
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AccountConcrete accountConcrete;
    private BigDecimal odrzavanjeRacuna= BigDecimal.ZERO;


    public CheckingAccount(AccountConcrete accountConcrete) {
        this.accountConcrete = accountConcrete;
    }

    @PrePersist
    @PreUpdate
    private void validate() {
        if (accountConcrete == null) {
            throw new IllegalStateException("accountConcrete je not null (ovde je teoretski moguce doci) ");
        }
        validacija(accountConcrete.getAccountOwnershipType());
        if (getCurrency() == null || getCurrency().getOznaka() != CurrencyCode.RSD) {
            throw new IllegalStateException("Mora RSD");
        }
    }

    @Override
    public void setCurrency(Currency currency) {
        if(currency.getOznaka()!=CurrencyCode.RSD)
            throw new IllegalArgumentException("Mora RSD");
        super.setCurrency(currency);
    }
}
