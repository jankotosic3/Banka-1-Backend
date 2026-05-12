package com.banka1.bankingcore.account.repository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;

/**
 * PR_06 C6.3: atomic balance update preko native SQL.
 *
 * <p>Pre PR_06, debit/credit su radjeni kroz:
 * <ol>
 *   <li>SELECT account
 *   <li>account.balance = account.balance - amount
 *   <li>UPDATE account SET ...
 * </ol>
 * Ovo je race-condition izlozeno: dva paralelna debit-a mogu oba procitati istu balance,
 * pa oba odbiti samo jedan amount → lost update.
 *
 * <p>Posle PR_06: jedan single SQL statement sa CHECK na sufficient balance:
 * <pre>
 * UPDATE accounts
 *    SET balance = balance - :amount,
 *        version = version + 1,
 *        updated_at = now()
 *  WHERE id = :id
 *    AND balance >= :amount
 *    AND deleted = false
 * </pre>
 * Vraca affected_rows: 0 = insufficient balance, 1 = OK. AccountService throws ako 0.
 *
 * <p>Atomic + native zaobilazi JPA dirty-check; combined sa @Version optimistic
 * locking-om garantuje no-lost-update na concurrent debits.
 */
@Component
public class AtomicBalanceUpdater {

    @PersistenceContext
    private EntityManager em;

    /**
     * @return broj affected rows (0 ako nema dovoljno balance-a, 1 ako je debit prosao).
     */
    @Transactional
    public int debit(Long accountId, BigDecimal amount) {
        return em.createNativeQuery("""
                UPDATE accounts
                   SET balance = balance - :amount,
                       version = version + 1,
                       updated_at = now()
                 WHERE id = :id
                   AND balance >= :amount
                   AND deleted = false
                """)
                .setParameter("amount", amount)
                .setParameter("id", accountId)
                .executeUpdate();
    }

    /**
     * Credit nema balance-check (uvek se moze dodati).
     */
    @Transactional
    public int credit(Long accountId, BigDecimal amount) {
        return em.createNativeQuery("""
                UPDATE accounts
                   SET balance = balance + :amount,
                       version = version + 1,
                       updated_at = now()
                 WHERE id = :id
                   AND deleted = false
                """)
                .setParameter("amount", amount)
                .setParameter("id", accountId)
                .executeUpdate();
    }

    /**
     * Atomic daily-limit decrement; vraca 0 ako bi limit isao ispod 0.
     */
    @Transactional
    public int debitDailyLimit(Long accountId, BigDecimal amount) {
        return em.createNativeQuery("""
                UPDATE accounts
                   SET daily_limit_remaining = daily_limit_remaining - :amount,
                       version = version + 1,
                       updated_at = now()
                 WHERE id = :id
                   AND daily_limit_remaining >= :amount
                   AND deleted = false
                """)
                .setParameter("amount", amount)
                .setParameter("id", accountId)
                .executeUpdate();
    }
}
