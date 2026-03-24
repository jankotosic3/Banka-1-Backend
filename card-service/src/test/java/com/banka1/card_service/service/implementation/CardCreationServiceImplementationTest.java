package com.banka1.card_service.service.implementation;

import com.banka1.card_service.domain.Card;
import com.banka1.card_service.domain.enums.CardBrand;
import com.banka1.card_service.domain.enums.CardStatus;
import com.banka1.card_service.domain.enums.CardType;
import com.banka1.card_service.dto.CardCreationResult;
import com.banka1.card_service.dto.GeneratedCvv;
import com.banka1.card_service.exception.BusinessException;
import com.banka1.card_service.repository.CardRepository;
import com.banka1.card_service.service.CardNumberGenerator;
import com.banka1.card_service.service.CvvService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardCreationServiceImplementationTest {

    @Mock
    private CardRepository cardRepository;

    @Mock
    private CardNumberGenerator cardNumberGenerator;

    @Mock
    private CvvService cvvService;

    private CardCreationServiceImplementation cardCreationService;

    @BeforeEach
    void setUp() {
        cardCreationService = new CardCreationServiceImplementation(cardRepository, cardNumberGenerator, cvvService);
    }

    @Test
    void createCardSetsDerivedFieldsAndPersistsHashedCvv() {
        when(cardNumberGenerator.generateCardNumber(CardBrand.VISA)).thenReturn("4111111111111111");
        when(cvvService.generateCvv()).thenReturn(new GeneratedCvv("123", "hashed-cvv"));
        when(cardRepository.save(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CardCreationResult result = cardCreationService.createCard(
                " 123-456-789 ",
                CardBrand.VISA,
                new BigDecimal("5000.00")
        );

        ArgumentCaptor<Card> cardCaptor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).save(cardCaptor.capture());
        Card savedCard = cardCaptor.getValue();

        assertEquals("4111111111111111", savedCard.getCardNumber());
        assertEquals(CardType.DEBIT, savedCard.getCardType());
        assertEquals("Visa Debit", savedCard.getCardName());
        assertEquals(savedCard.getCreationDate().plusYears(5), savedCard.getExpirationDate());
        assertEquals("123-456-789", savedCard.getAccountNumber());
        assertEquals("hashed-cvv", savedCard.getCvv());
        assertEquals(new BigDecimal("5000.00"), savedCard.getCardLimit());
        assertEquals(CardStatus.ACTIVE, savedCard.getStatus());
        assertEquals("123", result.plainCvv());
        assertTrue(result.card() == savedCard);
    }

    @Test
    void createCardRejectsBlankAccountNumber() {
        assertThrows(
                BusinessException.class,
                () -> cardCreationService.createCard("  ", CardBrand.VISA, new BigDecimal("1.00"))
        );
    }

    @Test
    void createCardRejectsNegativeCardLimit() {
        assertThrows(
                BusinessException.class,
                () -> cardCreationService.createCard("123", CardBrand.VISA, new BigDecimal("-1.00"))
        );
    }
}
