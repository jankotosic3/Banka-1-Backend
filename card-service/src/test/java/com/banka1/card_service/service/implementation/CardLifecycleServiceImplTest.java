package com.banka1.card_service.service.implementation;

import com.banka1.card_service.domain.Card;
import com.banka1.card_service.domain.enums.CardStatus;
import com.banka1.card_service.exception.BusinessException;
import com.banka1.card_service.rabbitMQ.RabbitClient;
import com.banka1.card_service.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardLifecycleServiceImplTest {

    @Mock
    private CardRepository cardRepository;

    @Mock
    private RabbitClient rabbitClient;

    private CardLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardLifecycleServiceImpl(cardRepository, rabbitClient);
    }

    // --- blockCard ---

    @Test
    void blockCard_activeCard_transitionsToBlocked() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.blockCard("1234");

        assertEquals(CardStatus.BLOCKED, card.getStatus());
        verify(cardRepository).save(card);
    }

    @Test
    void blockCard_blockedCard_throwsBusinessException() {
        Card card = cardWithStatus(CardStatus.BLOCKED);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));

        assertThrows(BusinessException.class, () -> service.blockCard("1234"));
        verify(cardRepository, never()).save(any());
    }

    @Test
    void blockCard_deactivatedCard_throwsBusinessException() {
        Card card = cardWithStatus(CardStatus.DEACTIVATED);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));

        assertThrows(BusinessException.class, () -> service.blockCard("1234"));
        verify(cardRepository, never()).save(any());
    }

    // --- unblockCard ---

    @Test
    void unblockCard_blockedCard_transitionsToActive() {
        Card card = cardWithStatus(CardStatus.BLOCKED);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.unblockCard("1234");

        assertEquals(CardStatus.ACTIVE, card.getStatus());
        verify(cardRepository).save(card);
    }

    @Test
    void unblockCard_activeCard_throwsBusinessException() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));

        assertThrows(BusinessException.class, () -> service.unblockCard("1234"));
        verify(cardRepository, never()).save(any());
    }

    @Test
    void unblockCard_deactivatedCard_throwsBusinessException() {
        Card card = cardWithStatus(CardStatus.DEACTIVATED);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));

        assertThrows(BusinessException.class, () -> service.unblockCard("1234"));
        verify(cardRepository, never()).save(any());
    }

    // --- deactivateCard ---

    @Test
    void deactivateCard_activeCard_transitionsToDeactivated() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deactivateCard("1234");

        assertEquals(CardStatus.DEACTIVATED, card.getStatus());
        verify(cardRepository).save(card);
    }

    @Test
    void deactivateCard_blockedCard_transitionsToDeactivated() {
        Card card = cardWithStatus(CardStatus.BLOCKED);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deactivateCard("1234");

        assertEquals(CardStatus.DEACTIVATED, card.getStatus());
        verify(cardRepository).save(card);
    }

    @Test
    void deactivateCard_deactivatedCard_throwsBusinessException() {
        Card card = cardWithStatus(CardStatus.DEACTIVATED);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));

        assertThrows(BusinessException.class, () -> service.deactivateCard("1234"));
        verify(cardRepository, never()).save(any());
    }

    // --- updateCardLimit ---

    @Test
    void updateCardLimit_validLimit_updatesCard() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        card.setCardLimit(BigDecimal.valueOf(1000));
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateCardLimit("1234", BigDecimal.valueOf(2000));

        assertEquals(BigDecimal.valueOf(2000), card.getCardLimit());
        verify(cardRepository).save(card);
    }

    @Test
    void updateCardLimit_zeroLimit_allowed() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));
        when(cardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateCardLimit("1234", BigDecimal.ZERO);

        assertEquals(BigDecimal.ZERO, card.getCardLimit());
    }

    @Test
    void updateCardLimit_negativeLimit_throwsBusinessException() {
        assertThrows(BusinessException.class,
                () -> service.updateCardLimit("1234", BigDecimal.valueOf(-1)));
        verify(cardRepository, never()).save(any());
    }

    @Test
    void updateCardLimit_nullLimit_throwsBusinessException() {
        assertThrows(BusinessException.class,
                () -> service.updateCardLimit("1234", null));
        verify(cardRepository, never()).save(any());
    }

    // --- card not found ---

    @Test
    void blockCard_cardNotFound_throwsBusinessException() {
        when(cardRepository.findByCardNumber("9999")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.blockCard("9999"));
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

    // --- getCardByCardNumber ---

    @Test
    void getCardByCardNumber_returnsCardDetail() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));

        var result = service.getCardByCardNumber("1234");

        assertEquals("1234", result.getCardNumber());
        assertEquals(CardStatus.ACTIVE, result.getStatus());
    }

    @Test
    void getCardByCardNumber_cardNotFound_throwsBusinessException() {
        when(cardRepository.findByCardNumber("9999")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.getCardByCardNumber("9999"));
    }

    // --- getCardsForClient ---

    @Test
    void getCardsForClient_returnsMaskedCards() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        card.setCardNumber("5798123456785571");
        when(cardRepository.findByClientId(1L)).thenReturn(List.of(card));

        var result = service.getCardsForClient(1L);

        assertEquals(1, result.size());
        assertEquals("5798********5571", result.get(0).getMaskedCardNumber());
    }

    @Test
    void getCardsForClient_noCards_returnsEmptyList() {
        when(cardRepository.findByClientId(99L)).thenReturn(List.of());

        var result = service.getCardsForClient(99L);

        assertEquals(0, result.size());
    }

    // --- getClientIdByCardNumber ---

    @Test
    void getClientIdByCardNumber_cardNotFound_throwsBusinessException() {
        when(cardRepository.findByCardNumber("9999")).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.getClientIdByCardNumber("9999"));
    }

    @Test
    void getClientIdByCardNumber_returnsCorrectClientId() {
        Card card = cardWithStatus(CardStatus.ACTIVE);
        card.setClientId(42L);
        when(cardRepository.findByCardNumber("1234")).thenReturn(Optional.of(card));

        Long clientId = service.getClientIdByCardNumber("1234");

        assertEquals(42L, clientId);
    }

    private Card cardWithStatus(CardStatus status) {
        Card card = new Card();
        card.setCardNumber("1234");
        card.setAccountNumber("265000000000123456");
        card.setClientId(1L);
        card.setCardLimit(BigDecimal.valueOf(1000));
        card.setCreationDate(LocalDate.now());
        card.setExpirationDate(LocalDate.now().plusYears(5));
        card.setStatus(status);
        return card;
    }
}
