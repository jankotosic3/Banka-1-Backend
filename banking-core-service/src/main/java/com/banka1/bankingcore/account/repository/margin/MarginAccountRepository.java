package com.banka1.bankingcore.account.repository.margin;

import com.banka1.bankingcore.account.domain.margin.MarginAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

/**
 * Bazni repozitorijum za sve marzne racune (UserMarginAccount + CompanyMarginAccount).
 * @{@link Lock}-ovi za pessimistic write koriste se u buy/sell flow-u radi sprecavanja
 * lost-update-a koji se moze desiti kada dva paralelna order-a pristupe istom racunu.
 */
@Repository
public interface MarginAccountRepository extends JpaRepository<MarginAccount, Long> {

    Optional<MarginAccount> findByAccountNumber(String accountNumber);

    /**
     * Pessimistic-write lock varijanta za update flow-ove (buy/sell, addTo/withdrawFrom).
     * PR_06 C6.5 prelazi sa @Version-only na pessimistic kombinaciju za hot path.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MarginAccount m where m.accountNumber = :accountNumber")
    Optional<MarginAccount> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);
}
