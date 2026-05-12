package com.banka1.card_service.repository;

import com.banka1.card_service.domain.Card;
import com.banka1.card_service.domain.enums.CardStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Counts non-terminal owner cards for one account.
     *
     * @param accountNumber linked account number
     * @param clientId owner client ID
     * @param status terminal status that should be excluded from the count
     * @return number of matching cards
     */
    long countByAccountNumberAndClientIdAndAuthorizedPersonIdIsNullAndStatusNot(
            String accountNumber,
            Long clientId,
            CardStatus status
    );

    /**
     * Counts non-terminal authorized-person cards for one account/person pair.
     *
     * @param accountNumber linked account number
     * @param authorizedPersonId authorized-person ID
     * @param status terminal status that should be excluded from the count
     * @return number of matching cards
     */
    long countByAccountNumberAndAuthorizedPersonIdAndStatusNot(
            String accountNumber,
            Long authorizedPersonId,
            CardStatus status
    );

    /**
     * Paginated bank-wide search across all cards for the employee
     * "Portal za upravljanje karticama" (PR_32).
     *
     * <p>Both filter parameters are optional. When {@code status} is {@code null} all statuses
     * are included; when {@code search} is {@code null} or blank no LIKE filter is applied.
     * The {@code search} term is matched (case-insensitive) against the raw card number,
     * the linked account number and the card brand label ({@code card_name}). Sorting is
     * applied through the {@link Pageable} argument — callers should pass a stable sort
     * (typically by {@code id} ASC) so paging returns deterministic windows.
     *
     * <p>The query intentionally targets fields available on the {@code cards} table only
     * so it stays a single SQL round trip — owner names are resolved client-side by
     * {@code CardLifecycleService} through the {@code client-service} REST adapter when
     * needed.
     *
     * @param status optional status filter (ACTIVE / BLOCKED / DEACTIVATED)
     * @param search optional case-insensitive substring matched against card number,
     *               account number or brand label
     * @param pageable paging + sort parameters
     * @return page of matching cards
     */
    @Query("""
        SELECT c FROM Card c
        WHERE (:status IS NULL OR c.status = :status)
          AND (
                :search IS NULL
             OR :search = ''
             OR LOWER(c.cardNumber)    LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(c.accountNumber) LIKE LOWER(CONCAT('%', :search, '%'))
             OR LOWER(c.cardName)      LIKE LOWER(CONCAT('%', :search, '%'))
          )
    """)
    Page<Card> searchCards(
            @Param("status") CardStatus status,
            @Param("search") String search,
            Pageable pageable
    );
}
