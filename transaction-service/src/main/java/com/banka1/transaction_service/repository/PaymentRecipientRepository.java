package com.banka1.transaction_service.repository;

import com.banka1.transaction_service.domain.PaymentRecipient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data repository za {@link PaymentRecipient}.
 * Sve metode automatski poštuju soft-delete restrikciju iz entiteta.
 */
@Repository
public interface PaymentRecipientRepository extends JpaRepository<PaymentRecipient, Long> {

    /** Vraca primaoca samo ako pripada datom vlasniku - sluzi i kao authorization gate. */
    Optional<PaymentRecipient> findByIdAndOwnerClientId(Long id, Long ownerClientId);

    /** Sve primaoce datog klijenta paginirano. */
    Page<PaymentRecipient> findByOwnerClientId(Long ownerClientId, Pageable pageable);

    /** Postoji li primalac sa istim nazivom kod ovog vlasnika (validacija pri create/update). */
    boolean existsByOwnerClientIdAndNaziv(Long ownerClientId, String naziv);
}
