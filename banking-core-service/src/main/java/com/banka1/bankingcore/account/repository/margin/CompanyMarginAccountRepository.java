package com.banka1.bankingcore.account.repository.margin;

import com.banka1.bankingcore.account.domain.margin.CompanyMarginAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompanyMarginAccountRepository extends JpaRepository<CompanyMarginAccount, Long> {

    Optional<CompanyMarginAccount> findByCompanyId(Long companyId);

    boolean existsByCompanyId(Long companyId);
}
