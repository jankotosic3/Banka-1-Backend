package com.banka1.account_service.domain;

import com.banka1.account_service.domain.enums.CurrencyCode;
import com.banka1.account_service.domain.enums.AccountOwnershipType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Getter
@Setter
@Entity
@DiscriminatorValue("FX")
@AllArgsConstructor
//todo fk firma instanca
public class FxAccount extends Account {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountOwnershipType accountOwnershipType;


    @PrePersist
    @PreUpdate
    private void validate() {
        if (accountOwnershipType == null) {
            throw new IllegalStateException("accountConcrete je not null (ovde je teoretski moguce doci) ");
        }
        validacija(accountOwnershipType);
        if (getCurrency() == null || getCurrency().getOznaka() == CurrencyCode.RSD) {
            throw new IllegalStateException("Ne moze RSD");
        }
    }

    public void setCurrency(Currency currency) {
        if(currency.getOznaka()== CurrencyCode.RSD)
            throw new IllegalArgumentException("Ne moze RSD");
        super.setCurrency(currency);
    }
}
