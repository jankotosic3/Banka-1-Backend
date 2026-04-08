package com.banka1.credit_service.repository;

import com.banka1.credit_service.domain.LoanRequest;
import com.banka1.credit_service.domain.enums.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanRequestRepository extends JpaRepository<LoanRequest,Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    UPDATE LoanRequest lr
    SET lr.status = :status
    WHERE lr.id = :id
      AND lr.status = com.banka1.credit_service.domain.enums.Status.PENDING
""")
    int updateStatus(@Param("id") Long id, @Param("status") Status status);
}
