package com.banka1.bankingcore.account.repository.margin;

import com.banka1.bankingcore.account.domain.margin.UserMarginAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserMarginAccountRepository extends JpaRepository<UserMarginAccount, Long> {

    /**
     * Spec: "korisnik moze imati samo jedan marzni racun" — uniqueness constraint je
     * na DB nivou; ovde omogucava lookup pre kreiranja da return-uje tacnu poruku.
     */
    Optional<UserMarginAccount> findByUserId(Long userId);

    boolean existsByUserId(Long userId);
}
