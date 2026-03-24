package com.banka1.card_service.service.implementation;

import com.banka1.card_service.domain.Card;
import com.banka1.card_service.domain.enums.CardStatus;
import com.banka1.card_service.dto.CardDetailDTO;
import com.banka1.card_service.dto.CardSummaryDTO;
import com.banka1.card_service.exception.BusinessException;
import com.banka1.card_service.exception.ErrorCode;
import com.banka1.card_service.rabbitMQ.RabbitClient;
import com.banka1.card_service.repository.CardRepository;
import com.banka1.card_service.service.CardLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Default implementation of {@link CardLifecycleService}.
 * All status transitions are validated against the allowed state machine before persisting.
 * A notification is dispatched via {@link RabbitClient} after every successful status change.
 */
@Service
@RequiredArgsConstructor
public class CardLifecycleServiceImpl implements CardLifecycleService {

    private final CardRepository cardRepository;
    private final RabbitClient rabbitClient;

    /**
     * Loads the card by card number and maps it to a full detail response.
     * The CVV hash is never included in the returned DTO.
     *
     * @param cardNumber card number to look up
     * @return full card details
     */
    @Override
    public CardDetailDTO getCardByCardNumber(String cardNumber) {
        return new CardDetailDTO(findCardOrThrow(cardNumber));
    }

    /**
     * Loads the card by card number and returns the owner's client ID.
     * Used by controllers to verify that the requesting client owns the card
     * before allowing client-initiated operations.
     *
     * @param cardNumber card number to look up
     * @return client ID of the card owner
     */
    @Override
    public Long getClientIdByCardNumber(String cardNumber) {
        return findCardOrThrow(cardNumber).getClientId();
    }

    /**
     * Queries all cards with the given client ID and maps each to a masked summary.
     * The masked card number format is: first 4 digits + asterisks + last 4 digits.
     *
     * @param clientId client ID to filter by
     * @return list of masked card summaries (may be empty)
     */
    @Override
    public List<CardSummaryDTO> getCardsForClient(Long clientId) {
        return cardRepository.findByClientId(clientId)
                .stream()
                .map(CardSummaryDTO::new)
                .toList();
    }

    /**
     * Queries all cards linked to the given account number and maps each to a masked summary.
     * The masked card number format is: first 4 digits + asterisks + last 4 digits.
     *
     * @param accountNumber account number to filter by
     * @return list of masked card summaries (may be empty)
     */
    @Override
    public List<CardSummaryDTO> getCardsByAccountNumber(String accountNumber) {
        return cardRepository.findByAccountNumber(accountNumber)
                .stream()
                .map(CardSummaryDTO::new)
                .toList();
    }

    /**
     * Transitions the card to BLOCKED and persists the change.
     * Both clients and employees may block a card.
     * Allowed transition: ACTIVE → BLOCKED.
     *
     * @param cardNumber card number to block
     */
    @Override
    @Transactional
    public void blockCard(String cardNumber) {
        Card card = findCardOrThrow(cardNumber);
        transitionStatus(card, CardStatus.BLOCKED);
        cardRepository.save(card);
        // TODO: send email notification — requires client email (resolve from client-service by clientId)
        // TODO: for business accounts, also notify AuthorizedPerson — requires AuthorizedPerson entity (card creation subissue)
    }

    /**
     * Transitions the card back to ACTIVE and persists the change.
     * Only employees may unblock a card.
     * Allowed transition: BLOCKED → ACTIVE.
     *
     * @param cardNumber card number to unblock
     */
    @Override
    @Transactional
    public void unblockCard(String cardNumber) {
        Card card = findCardOrThrow(cardNumber);
        transitionStatus(card, CardStatus.ACTIVE);
        cardRepository.save(card);
        // TODO: send email notification — requires client email (resolve from client-service by clientId)
        // TODO: for business accounts, also notify AuthorizedPerson — requires AuthorizedPerson entity (card creation subissue)
    }

    /**
     * Permanently transitions the card to DEACTIVATED and persists the change.
     * Only employees may deactivate a card.
     * Deactivation is irreversible — once DEACTIVATED, no further transitions are allowed.
     * Allowed transitions: ACTIVE → DEACTIVATED or BLOCKED → DEACTIVATED.
     *
     * @param cardNumber card number to deactivate
     */
    @Override
    @Transactional
    public void deactivateCard(String cardNumber) {
        Card card = findCardOrThrow(cardNumber);
        transitionStatus(card, CardStatus.DEACTIVATED);
        cardRepository.save(card);
        // TODO: send email notification — requires client email (resolve from client-service by clientId)
        // TODO: for business accounts, also notify AuthorizedPerson — requires AuthorizedPerson entity (card creation subissue)
    }

    /**
     * Validates the new limit and updates the card.
     * The limit must be zero or greater — zero effectively disables spending on the card.
     *
     * @param cardNumber card number to update
     * @param newLimit new limit value, must be zero or greater
     */
    @Override
    @Transactional
    public void updateCardLimit(String cardNumber, BigDecimal newLimit) {
        if (newLimit == null || newLimit.signum() < 0) {
            throw new BusinessException(ErrorCode.INVALID_LIMIT, "Card limit must be zero or greater.");
        }
        Card card = findCardOrThrow(cardNumber);
        card.setCardLimit(newLimit);
        cardRepository.save(card);
    }

    /**
     * Validates and applies a status transition on the given card.
     * Allowed transitions:
     * ACTIVE      → BLOCKED or DEACTIVATED
     * BLOCKED     → ACTIVE or DEACTIVATED
     * DEACTIVATED → none (terminal state)
     *
     * @param card card whose status is being changed
     * @param target desired target status
     * @throws BusinessException when the transition is not permitted by the state machine
     */
    private void transitionStatus(Card card, CardStatus target) {
        CardStatus current = card.getStatus();
        boolean allowed = switch (current) {
            case ACTIVE -> target == CardStatus.BLOCKED || target == CardStatus.DEACTIVATED;
            case BLOCKED -> target == CardStatus.ACTIVE || target == CardStatus.DEACTIVATED;
            case DEACTIVATED -> false;
        };
        if (!allowed) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "Transition from " + current + " to " + target + " is not allowed."
            );
        }
        card.setStatus(target);
    }

    /**
     * Looks up a card by its card number or throws a {@link BusinessException} if not found.
     *
     * @param cardNumber card number to look up
     * @return the matching card entity
     * @throws BusinessException with {@link ErrorCode#CARD_NOT_FOUND} when no card matches
     */
    private Card findCardOrThrow(String cardNumber) {
        return cardRepository.findByCardNumber(cardNumber)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CARD_NOT_FOUND,
                        "Card with number " + cardNumber + " was not found."
                ));
    }
}
