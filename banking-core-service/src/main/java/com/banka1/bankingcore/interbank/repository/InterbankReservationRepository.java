package com.banka1.bankingcore.interbank.repository;

import com.banka1.bankingcore.interbank.model.InterbankReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository za interbank rezervacije (PR_32 Phase 11).
 */
@Repository
public interface InterbankReservationRepository extends JpaRepository<InterbankReservation, Long> {

    Optional<InterbankReservation> findByReservationId(UUID reservationId);

    List<InterbankReservation> findByTransactionIdRoutingAndTransactionIdLocal(
            int transactionIdRouting, String transactionIdLocal);
}
