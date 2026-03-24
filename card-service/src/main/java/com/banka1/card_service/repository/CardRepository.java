package com.banka1.card_service.repository;

import com.banka1.card_service.domain.Card;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for persisted cards.
 */
public interface CardRepository extends JpaRepository<Card, Long> {

    /**
     * Checks whether a card number is already in use.
     *
     * @param cardNumber card number to check
     * @return {@code true} when a card with the number already exists
     */
    boolean existsByCardNumber(String cardNumber);

    /**
     * Finds a card by its card number.
     *
     * @param cardNumber card number to look up
     * @return the matching card, or empty if not found
     */
    Optional<Card> findByCardNumber(String cardNumber);

    /**
     * Returns all cards linked to a given bank account number.
     *
     * @param accountNumber account number to filter by
     * @return list of cards for the account (may be empty)
     */
    List<Card> findByAccountNumber(String accountNumber);

    /**
     * Returns all cards owned by a given client.
     *
     * @param clientId client ID to filter by
     * @return list of cards owned by the client (may be empty)
     */
    List<Card> findByClientId(Long clientId);
}
