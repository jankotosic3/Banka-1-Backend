package com.banka1.bankingcore.account.domain.margin;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Marzni racun za pravno lice (kompaniju) — Spec: "CompanyMarginAccount ce biti
 * marzni racun za kompanije". Diskriminator vrednost: "COMPANY".
 */
@Entity
@Table(
        name = "company_margin_accounts",
        uniqueConstraints = {
                // Spec: "kompanija moze imati samo jedan marzni racun".
                @UniqueConstraint(name = "uk_company_margin_account_company_id", columnNames = "company_id")
        }
)
@DiscriminatorValue("COMPANY")
@PrimaryKeyJoinColumn(name = "id")
@NoArgsConstructor
@Getter
@Setter
public class CompanyMarginAccount extends MarginAccount {

    /**
     * ID kompanije kojoj racun pripada (FK ka companies.id, koja postoji u banking-core-u
     * kao deo client/company management-a).
     */
    @NotNull
    @Column(name = "company_id", nullable = false)
    private Long companyId;
}
