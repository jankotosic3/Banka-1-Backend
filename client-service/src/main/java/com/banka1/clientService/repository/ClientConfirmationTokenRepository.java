package com.banka1.clientService.repository;

import com.banka1.clientService.domain.ClientConfirmationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repozitorijum za entitet {@link ClientConfirmationToken}.
 */
@Repository
public interface ClientConfirmationTokenRepository extends JpaRepository<ClientConfirmationToken, Long> {

    /**
     * Pronalazi token po hesiranoj vrednosti.
     *
     * @param value SHA-256 hash tokena
     * @return opcioni token ako postoji
     */
    Optional<ClientConfirmationToken> findByValue(String value);
}
