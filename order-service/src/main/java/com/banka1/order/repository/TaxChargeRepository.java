package com.banka1.order.repository;

import com.banka1.order.entity.TaxCharge;
import com.banka1.order.entity.enums.TaxChargeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxChargeRepository extends JpaRepository<TaxCharge, Long> {
    boolean existsBySellTransactionIdAndBuyTransactionId(Long sellTransactionId, Long buyTransactionId);
    boolean existsByOtcContractId(Long otcContractId);
    List<TaxCharge> findByUserIdAndStatus(Long userId, TaxChargeStatus status);
}
