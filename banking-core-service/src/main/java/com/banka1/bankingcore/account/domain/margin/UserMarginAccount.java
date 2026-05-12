package com.banka1.bankingcore.account.domain.margin;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Marzni racun za fizicko lice — Spec: "UserMarginAccount potklasa ce biti racun za korisnike".
 * Diskriminator vrednost: "USER".
 */
@Entity
@Table(
        name = "user_margin_accounts",
        uniqueConstraints = {
                // Spec: "korisnik moze imati samo jedan marzni racun".
                @UniqueConstraint(name = "uk_user_margin_account_user_id", columnNames = "user_id")
        }
)
@DiscriminatorValue("USER")
@PrimaryKeyJoinColumn(name = "id")
@NoArgsConstructor
@Getter
@Setter
public class UserMarginAccount extends MarginAccount {

    /**
     * ID korisnika kome racun pripada (FK ka {@code clients.id} u user-service-u
     * kroz REST poziv pri kreiranju — banking-core ne drzi FK ka cross-service tabeli).
     */
    @NotNull
    @Column(name = "user_id", nullable = false)
    private Long userId;
}
