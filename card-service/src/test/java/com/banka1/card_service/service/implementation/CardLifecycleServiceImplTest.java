package com.banka1.card_service.service.implementation;

import com.banka1.card_service.domain.Card;
import com.banka1.card_service.domain.enums.AccountOwnershipType;
import com.banka1.card_service.domain.enums.CardStatus;
import com.banka1.card_service.dto.card_management.internal.CardNotificationDto;
import com.banka1.card_service.dto.enums.CardNotificationType;
import com.banka1.card_service.exception.BusinessException;
import com.banka1.card_service.rabbitMQ.RabbitClient;
import com.banka1.card_service.repository.AuthorizedPersonRepository;
import com.banka1.card_service.rest_client.AccountNotificationContextDto;
import com.banka1.card_service.rest_client.AccountService;
import com.banka1.card_service.rest_client.ClientNotificationRecipientDto;
import com.banka1.card_service.rest_client.ClientService;
import com.banka1.card_service.repository.CardRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardLifecycleServiceImplTest {

    @Mock
    private CardRepository cardRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private AuthorizedPersonRepository authorizedPersonRepository;

    @Mock
    private ClientService clientService;

    @Mock
    private RabbitClient rabbitClient;

    private CardLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        service = new CardLifecycleServiceImpl(
                cardRepository,
                authorizedPersonRepository,
                accountService,
                clientService,
                rabbitClient
        );
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // --- blockCard ---

    @Test
    void blockCard_activeCard_transitionsToBlocked() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.getNotificationContext("265000000000123456")).thenReturn(personalAccount());
        when(clientService.getNotificationRecipient(1L)).thenReturn(recipient());

        service.blockCard(15L);

        assertEquals(CardStatus.BLOCKED, card.getStatus());
        verify(cardRepository).save(card);
        verify(rabbitClient, never()).sendCardNotification(any(), any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(rabbitClient).sendCardNotification(eq(CardNotificationType.CARD_BLOCKED), any(CardNotificationDto.class));
    }

    @Test
    void blockCard_businessAccount_sendsToAuthorizedPersonAndOwner() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.getNotificationContext("265000000000123456")).thenReturn(businessAccount(2L));
        when(clientService.getNotificationRecipient(1L)).thenReturn(recipient());
        when(clientService.getNotificationRecipient(2L)).thenReturn(ownerRecipient());

        service.blockCard(15L);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(clientService).getNotificationRecipient(1L);
        verify(clientService).getNotificationRecipient(2L);
        verify(rabbitClient, times(2))
                .sendCardNotification(eq(CardNotificationType.CARD_BLOCKED), any(CardNotificationDto.class));
    }

    @Test
    void blockCard_businessAccountWithSameOwnerAndCardHolder_sendsSingleEmail() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.getNotificationContext("265000000000123456")).thenReturn(businessAccount(1L));
        when(clientService.getNotificationRecipient(1L)).thenReturn(recipient());

        service.blockCard(15L);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(clientService, times(1)).getNotificationRecipient(1L);
        verify(rabbitClient, times(1))
                .sendCardNotification(eq(CardNotificationType.CARD_BLOCKED), any(CardNotificationDto.class));
    }

    @Test
    void blockCard_blockedCard_throwsBusinessException() {
        Card card = cardWithStatus(CardStatus.BLOCKED);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));

        assertThrows(BusinessException.class, () -> service.blockCard(15L));
        verify(cardRepository, never()).save(any());
    }

    @Test
    void blockCard_deactivatedCard_throwsBusinessException() {
        Card card = cardWithStatus(CardStatus.DEACTIVATED);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));

        assertThrows(BusinessException.class, () -> service.blockCard(15L));
        verify(cardRepository, never()).save(any());
    }

    // --- unblockCard ---

    @Test
    void unblockCard_blockedCard_transitionsToActive() {
        Card card = cardWithStatus(CardStatus.BLOCKED);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.getNotificationContext("265000000000123456")).thenReturn(personalAccount());
        when(clientService.getNotificationRecipient(1L)).thenReturn(recipient());

        service.unblockCard(15L);

        assertEquals(CardStatus.ACTIVE, card.getStatus());
        verify(cardRepository).save(card);
        verify(rabbitClient, never()).sendCardNotification(any(), any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(rabbitClient).sendCardNotification(eq(CardNotificationType.CARD_UNBLOCKED), any(CardNotificationDto.class));
    }

    @Test
    void unblockCard_activeCard_throwsBusinessException() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));

        assertThrows(BusinessException.class, () -> service.unblockCard(15L));
        verify(cardRepository, never()).save(any());
    }

    @Test
    void unblockCard_deactivatedCard_throwsBusinessException() {
        Card card = cardWithStatus(CardStatus.DEACTIVATED);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));

        assertThrows(BusinessException.class, () -> service.unblockCard(15L));
        verify(cardRepository, never()).save(any());
    }

    // --- deactivateCard ---

    @Test
    void deactivateCard_activeCard_transitionsToDeactivated() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.getNotificationContext("265000000000123456")).thenReturn(personalAccount());
        when(clientService.getNotificationRecipient(1L)).thenReturn(recipient());

        service.deactivateCard(15L);

        assertEquals(CardStatus.DEACTIVATED, card.getStatus());
        verify(cardRepository).save(card);
        verify(rabbitClient, never()).sendCardNotification(any(), any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(rabbitClient).sendCardNotification(eq(CardNotificationType.CARD_DEACTIVATED), any(CardNotificationDto.class));
    }

    @Test
    void deactivateCard_blockedCard_transitionsToDeactivated() {
        Card card = cardWithStatus(CardStatus.BLOCKED);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountService.getNotificationContext("265000000000123456")).thenReturn(personalAccount());
        when(clientService.getNotificationRecipient(1L)).thenReturn(recipient());

        service.deactivateCard(15L);

        assertEquals(CardStatus.DEACTIVATED, card.getStatus());
        verify(cardRepository).save(card);
    }

    @Test
    void deactivateCard_deactivatedCard_throwsBusinessException() {
        Card card = cardWithStatus(CardStatus.DEACTIVATED);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));

        assertThrows(BusinessException.class, () -> service.deactivateCard(15L));
        verify(cardRepository, never()).save(any());
    }

    @Test
    void unblockCard_cardNotFound_throwsBusinessException() {
        when(cardRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.unblockCard(9999L));
    }

    @Test
    void deactivateCard_cardNotFound_throwsBusinessException() {
        when(cardRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.deactivateCard(9999L));
    }

    // --- updateCardLimit ---

    @Test
    void updateCardLimit_validLimit_updatesCard() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        card.setCardLimit(BigDecimal.valueOf(1000));
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateCardLimit(15L, BigDecimal.valueOf(2000));

        assertEquals(BigDecimal.valueOf(2000), card.getCardLimit());
        verify(cardRepository).save(card);
    }

    @Test
    void updateCardLimit_zeroLimit_allowed() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateCardLimit(15L, BigDecimal.ZERO);

        assertEquals(BigDecimal.ZERO, card.getCardLimit());
    }

    @Test
    void updateCardLimit_negativeLimit_throwsBusinessException() {
        assertThrows(BusinessException.class,
                () -> service.updateCardLimit(15L, BigDecimal.valueOf(-1)));
        verify(cardRepository, never()).save(any());
    }

    @Test
    void updateCardLimit_nullLimit_throwsBusinessException() {
        assertThrows(BusinessException.class,
                () -> service.updateCardLimit(15L, null));
        verify(cardRepository, never()).save(any());
    }

    // --- card not found ---

    @Test
    void blockCard_cardNotFound_throwsBusinessException() {
        when(cardRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.blockCard(9999L));
    }

    @Test
    void getCardsByAccountNumber_returnsListWithMaskedNumbers() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        card.setCardNumber("5798123456785571");
        card.setAccountNumber("265000000000123456");
        when(cardRepository.findByAccountNumber("265000000000123456")).thenReturn(List.of(card));

        var result = service.getCardsByAccountNumber("265000000000123456");

        assertEquals(1, result.size());
        assertEquals("5798********5571", result.get(0).getMaskedCardNumber());
    }

    @Test
    void getInternalCardsByAccountNumber_returnsInternalSummaries() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        card.setCardNumber("5798123456785571");
        card.setCardName("Visa Debit");
        card.setAccountNumber("265000000000123456");
        when(cardRepository.findByAccountNumber("265000000000123456")).thenReturn(List.of(card));

        var result = service.getInternalCardsByAccountNumber("265000000000123456");

        assertEquals(1, result.size());
        assertEquals("5798********5571", result.get(0).getCardNumber());
        assertEquals("Visa Debit", result.get(0).getCardType());
        assertEquals("ACTIVE", result.get(0).getStatus());
        assertEquals("265000000000123456", result.get(0).getAccountNumber());
    }

    // --- getCardById ---

    @Test
    void getCardById_returnsCardDetail() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));

        var result = service.getCardById(15L);

        assertEquals(15L, result.getId());
        assertEquals("1234", result.getCardNumber());
        assertEquals(CardStatus.ACTIVE, result.getStatus());
    }

    @Test
    void getCardById_cardNotFound_throwsBusinessException() {
        when(cardRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.getCardById(9999L));
    }

    // --- getCardsForClient ---

    @Test
    void getCardsForClient_returnsMaskedCards() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        card.setCardNumber("5798123456785571");
        when(cardRepository.findByClientId(1L)).thenReturn(List.of(card));

        var result = service.getCardsForClient(1L);

        assertEquals(1, result.size());
        assertEquals(15L, result.get(0).getId());
        assertEquals("5798********5571", result.get(0).getMaskedCardNumber());
    }

    @Test
    void getCardsForClient_noCards_returnsEmptyList() {
        when(cardRepository.findByClientId(99L)).thenReturn(List.of());

        var result = service.getCardsForClient(99L);

        assertEquals(0, result.size());
    }

    // --- getClientIdByCardId ---

    @Test
    void getClientIdByCardId_cardNotFound_throwsBusinessException() {
        when(cardRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.getClientIdByCardId(9999L));
    }

    @Test
    void getClientIdByCardId_returnsCorrectClientId() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        card.setClientId(42L);
        when(cardRepository.findById(15L)).thenReturn(Optional.of(card));

        Long clientId = service.getClientIdByCardId(15L);

        assertEquals(42L, clientId);
    }

    private Card cardWithStatus(CardStatus status) {
        Card card = new Card();
        card.setId(15L);
        card.setCardNumber("1234");
        card.setAccountNumber("265000000000123456");
        card.setClientId(1L);
        card.setCardName("Visa Debit");
        card.setCardLimit(BigDecimal.valueOf(1000));
        card.setCreationDate(LocalDate.now());
        card.setExpirationDate(LocalDate.now().plusYears(5));
        card.setStatus(status);
        return card;
    }

    private ClientNotificationRecipientDto recipient() {
        return new ClientNotificationRecipientDto(1L, "Pera", "Peric", "pera@example.com");
    }

    private ClientNotificationRecipientDto ownerRecipient() {
        return new ClientNotificationRecipientDto(2L, "Mika", "Mikic", "mika@example.com");
    }

    private AccountNotificationContextDto personalAccount() {
        return new AccountNotificationContextDto(AccountOwnershipType.PERSONAL, null);
    }

    private AccountNotificationContextDto businessAccount(Long ownerClientId) {
        return new AccountNotificationContextDto(AccountOwnershipType.BUSINESS, ownerClientId);
    }
}
