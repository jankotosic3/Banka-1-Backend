package com.banka1.card_service.service.implementation;

import com.banka1.card_service.domain.Card;
import com.banka1.card_service.domain.enums.CardStatus;
import com.banka1.card_service.dto.card_management.internal.CardNotificationDto;
import com.banka1.card_service.dto.card_management.response.CardDetailDTO;
import com.banka1.card_service.dto.card_management.response.CardInternalSummaryDTO;
import com.banka1.card_service.dto.card_management.response.CardSummaryDTO;
import com.banka1.card_service.dto.enums.CardNotificationType;
import com.banka1.card_service.exception.BusinessException;
import com.banka1.card_service.exception.ErrorCode;
import com.banka1.card_service.rabbitMQ.RabbitClient;
import com.banka1.card_service.rest_client.AccountNotificationContextDto;
import com.banka1.card_service.rest_client.AccountService;
import com.banka1.card_service.rest_client.ClientNotificationRecipientDto;
import com.banka1.card_service.rest_client.ClientService;
import com.banka1.card_service.repository.AuthorizedPersonRepository;
import com.banka1.card_service.repository.CardRepository;
import com.banka1.card_service.service.CardLifecycleService;
import com.banka1.card_service.util.SensitiveDataMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link CardLifecycleService}.
 * All status transitions are validated against the allowed state machine before persisting.
 * A notification is dispatched via RabbitMQ after every successful status change.
 */
@Service
@RequiredArgsConstructor
public class CardLifecycleServiceImpl implements CardLifecycleService {

    private final CardRepository cardRepository;
    private final AuthorizedPersonRepository authorizedPersonRepository;
    private final AccountService accountService;
    private final ClientService clientService;
    private final RabbitClient rabbitClient;

    /**
     * Loads the card by ID and maps it to a full detail response.
     * The CVV hash is never included in the returned DTO.
     *
     * @param cardId card ID to look up
     * @return full card details
     */
    @Override
    public CardDetailDTO getCardById(Long cardId) {
        return new CardDetailDTO(findCardByIdOrThrow(cardId));
    }

    /**
     * Loads the card by ID and returns the owner's client ID.
     * Used by controllers to verify that the requesting client owns the card
     * before allowing client-initiated operations.
     *
     * @param cardId card ID to look up
     * @return client ID of the card owner
     */
    @Override
    public Long getClientIdByCardId(Long cardId) {
        return findCardByIdOrThrow(cardId).getClientId();
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

    @Override
    public List<CardInternalSummaryDTO> getInternalCardsByAccountNumber(String accountNumber) {
        return cardRepository.findByAccountNumber(accountNumber)
                .stream()
                .map(CardInternalSummaryDTO::new)
                .toList();
    }

    /**
     * Transitions the card to BLOCKED and persists the change.
     * Both clients and employees may block a card.
     * Allowed transition: ACTIVE → BLOCKED.
     *
     * @param cardId card ID to block
     */
    @Override
    @Transactional
    public void blockCard(Long cardId) {
        Card card = findCardByIdOrThrow(cardId);
        transitionStatus(card, CardStatus.BLOCKED);
        cardRepository.save(card);
        registerAfterCommitNotification(card, CardNotificationType.CARD_BLOCKED);
    }

    /**
     * Transitions the card back to ACTIVE and persists the change.
     * Only employees may unblock a card.
     * Allowed transition: BLOCKED → ACTIVE.
     *
     * @param cardId card ID to unblock
     */
    @Override
    @Transactional
    public void unblockCard(Long cardId) {
        Card card = findCardByIdOrThrow(cardId);
        transitionStatus(card, CardStatus.ACTIVE);
        cardRepository.save(card);
        registerAfterCommitNotification(card, CardNotificationType.CARD_UNBLOCKED);
    }

    /**
     * Permanently transitions the card to DEACTIVATED and persists the change.
     * Only employees may deactivate a card.
     * Deactivation is irreversible — once DEACTIVATED, no further transitions are allowed.
     * Allowed transitions: ACTIVE → DEACTIVATED or BLOCKED → DEACTIVATED.
     *
     * @param cardId card ID to deactivate
     */
    @Override
    @Transactional
    public void deactivateCard(Long cardId) {
        Card card = findCardByIdOrThrow(cardId);
        transitionStatus(card, CardStatus.DEACTIVATED);
        cardRepository.save(card);
        registerAfterCommitNotification(card, CardNotificationType.CARD_DEACTIVATED);
    }

    /**
     * Validates the new limit and updates the card.
     * The limit must be zero or greater — zero effectively disables spending on the card.
     *
     * @param cardId card ID to update
     * @param newLimit new limit value, must be zero or greater
     */
    @Override
    @Transactional
    public void updateCardLimit(Long cardId, BigDecimal newLimit) {
        if (newLimit == null || newLimit.signum() < 0) {
            throw new BusinessException(ErrorCode.INVALID_LIMIT, "Card limit must be zero or greater.");
        }
        Card card = findCardByIdOrThrow(cardId);
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
     * Looks up a card by its database ID or throws a {@link BusinessException} if not found.
     *
     * @param cardId card ID to look up
     * @return the matching card entity
     * @throws BusinessException with {@link ErrorCode#CARD_NOT_FOUND} when no card matches
     */
    private Card findCardByIdOrThrow(Long cardId) {
        return cardRepository.findById(cardId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CARD_NOT_FOUND,
                        "Card with ID " + cardId + " was not found."
                ));
    }

    /**
     * Resolves the notification recipient from {@code client-service}, builds the RabbitMQ payload,
     * and schedules message publishing strictly after the surrounding transaction commits.
     *
     * <p>This avoids sending a notification for a status change that later rolls back.
     * Recipient lookup is done before {@code afterCommit()} so the callback stays lightweight
     * and does not depend on an active transactional context.
     *
     * @param card card whose lifecycle change triggered the notification
     * @param notificationType concrete routing-key descriptor for the outgoing event
     */
    private void registerAfterCommitNotification(Card card, CardNotificationType notificationType) {
        List<CardNotificationDto> payloads = resolveNotificationRecipients(card)
                .stream()
                .map(recipient -> buildNotificationPayload(card, recipient))
                .toList();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                payloads.forEach(payload -> rabbitClient.sendCardNotification(notificationType, payload));
            }
        });
    }

    /**
     * Resolves all recipients that must receive a card lifecycle notification.

     * 1 notification always goes to the client tied directly to the card
     * ({@code card.clientId}).

     * For business accounts, a 2. notification goes to the business-account
     * owner returned by {@code account-service}.
     * When both IDs point to the same person, only one email is sent.
     *
     * @param card card whose lifecycle changed
     * @return ordered, de-duplicated recipient list
     */
    private List<ClientNotificationRecipientDto> resolveNotificationRecipients(Card card) {
        List<ClientNotificationRecipientDto> recipients = new ArrayList<>();
        Set<Long> seenClientIds = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();

        addRecipientIfPresent(recipients, seenClientIds, card.getClientId());

        if (card.getAuthorizedPersonId() != null) {
            authorizedPersonRepository.findById(card.getAuthorizedPersonId())
                    .ifPresent(authorizedPerson -> addDirectRecipientIfPresent(
                            recipients,
                            seenEmails,
                            authorizedPerson.getFirstName(),
                            authorizedPerson.getLastName(),
                            authorizedPerson.getEmail()
                    ));
        } else {
            AccountNotificationContextDto accountContext = accountService.getNotificationContext(card.getAccountNumber());
            if (accountContext != null && accountContext.isBusinessAccount()) {
                addRecipientIfPresent(recipients, seenClientIds, accountContext.ownerClientId());
            }
        }

        return recipients;
    }

    /**
     * Adds a recipient resolved through {@code client-service} when the client ID is present
     * and has not already been included in the current notification batch.
     *
     * <p>This prevents duplicate notifications when multiple resolution paths point
     * to the same client.
     *
     * @param recipients mutable list of resolved recipients
     * @param seenClientIds set used for client-ID de-duplication
     * @param clientId candidate client ID to resolve
     */
    private void addRecipientIfPresent(
            List<ClientNotificationRecipientDto> recipients,
            Set<Long> seenClientIds,
            Long clientId
    ) {
        if (clientId == null || !seenClientIds.add(clientId)) {
            return;
        }
        recipients.add(clientService.getNotificationRecipient(clientId));
    }

    /**
     * Adds a direct email recipient when the address is non-blank and not already present.
     *
     * <p>Email de-duplication is case-insensitive and ignores surrounding whitespace,
     * while the payload keeps the trimmed email address.
     *
     * @param recipients mutable list of resolved recipients
     * @param seenEmails set of normalized email addresses already included
     * @param firstName recipient first name
     * @param lastName recipient last name
     * @param email candidate email address
     */
    private void addDirectRecipientIfPresent(
            List<ClientNotificationRecipientDto> recipients,
            Set<String> seenEmails,
            String firstName,
            String lastName,
            String email
    ) {
        if (email == null || email.isBlank()) {
            return;
        }
        String normalizedEmail = email.trim().toLowerCase();
        if (!seenEmails.add(normalizedEmail)) {
            return;
        }
        recipients.add(new ClientNotificationRecipientDto(null, firstName, lastName, email.trim()));
    }

    /**
     * Adapts internal card and recipient data to the payload format expected by
     * {@code notification-service}.
     *
     * <p>The payload uses the recipient display name and email returned from
     * {@code client-service}, while the card-specific values are injected as template variables.
     *
     * @param card source card entity
     * @param recipient resolved notification recipient
     * @return outbound RabbitMQ payload for the notification consumer
     */
    private CardNotificationDto buildNotificationPayload(Card card, ClientNotificationRecipientDto recipient) {
        return new CardNotificationDto(
                recipient.displayName(),
                recipient.email(),
                templateVariables(card)
        );
    }

    /**
     * Builds the variable map consumed by notification templates for card events.
     *
     * <p>Sensitive identifiers are masked before being added to the template model.
     * The resulting keys are aligned with the placeholders defined in
     * {@code notification-service} templates.
     *
     * @param card source card entity
     * @return ordered template-variable map
     */
    private Map<String, String> templateVariables(Card card) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("cardNumber", SensitiveDataMasker.maskCardNumber(card.getCardNumber()));
        variables.put("accountNumber", SensitiveDataMasker.maskAccountNumber(card.getAccountNumber()));
        variables.put("cardName", card.getCardName());
        return variables;
    }
}
