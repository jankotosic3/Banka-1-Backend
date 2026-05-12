package com.banka1.bankingcore.transaction.repository.margin;

import com.banka1.bankingcore.transaction.domain.margin.MarginTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MarginTransactionRepository extends JpaRepository<MarginTransaction, Long> {

    /**
     * Spec: "/getAllMarginTransactions/{accountNumber} vraca i placanja i primanja novca".
     * Sortiramo descending da klijent vidi najnovije prvo.
     */
    List<MarginTransaction> findByAccountNumberOrderByOccurredAtDesc(String accountNumber);
}
