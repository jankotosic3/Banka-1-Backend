package com.banka1.card_service.service.implementation;

import com.banka1.card_service.domain.Card;
import com.banka1.card_service.domain.enums.CardBrand;
import com.banka1.card_service.domain.enums.CardStatus;
import com.banka1.card_service.domain.enums.CardType;
import com.banka1.card_service.dto.CardCreationResult;
import com.banka1.card_service.dto.GeneratedCvv;
import com.banka1.card_service.exception.BusinessException;
import com.banka1.card_service.exception.ErrorCode;
import com.banka1.card_service.repository.CardRepository;
import com.banka1.card_service.service.CardCreationService;
import com.banka1.card_service.service.CardNumberGenerator;
import com.banka1.card_service.service.CvvService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Default implementation of {@link CardCreationService}.
 * This is the main service for CREATING NEW CARD
 * This class orchestrates the complete debit-card creation flow.
 * It validates business inputs, generates a unique brand-compliant card number,
 * generates a 3-digit CVV and hashes it, derives product defaults such as
 *  - type,
 *  - name,
 *  - dates,
 *  - and initial status,
 *  and persists the resulting entity.
 *
 * Example:
 * for brand {@code VISA} and limit {@code 5000.00}, the created entity will be named
 * {@code "Visa Debit"}, will have status {@code ACTIVE}, and will expire five years after creation.
 */
@Service
public class CardCreationServiceImplementation implements CardCreationService {

    /**
     * Repository used to persist the fully prepared card entity.
     */
    private final CardRepository cardRepository;

    /**
     * Generator responsible for unique, brand-compliant card numbers.
     */
    private final CardNumberGenerator cardNumberGenerator;

    /**
     * Service responsible for secure CVV generation and hashing.
     */
    private final CvvService cvvService;

    /**
     * Creates the service implementation with all required collaborators.
     *
     * @param cardRepository repository used to save cards
     * @param cardNumberGenerator generator used for card numbers
     * @param cvvService service used for CVV generation and hashing
     */
    public CardCreationServiceImplementation(
            CardRepository cardRepository,
            CardNumberGenerator cardNumberGenerator,
            CvvService cvvService
    ) {
        this.cardRepository = cardRepository;
        this.cardNumberGenerator = cardNumberGenerator;
        this.cvvService = cvvService;
    }

    /**
     * Creates and persists a new debit card for the provided account.
     * The method trims the account number, sets the business creation date to today,
     * automatically computes expiration as five years later, and stores only the hashed CVV.
     *
     * @param accountNumber linked account number that will own the card
     * @param cardBrand brand used for card-number generation and display naming
     * @param cardLimit spending limit to persist on the card
     * @return persisted card together with the one-time plain CVV
     */
    @Override
    public CardCreationResult createCard(String accountNumber, CardBrand cardBrand, BigDecimal cardLimit) {
        validateInput(accountNumber, cardLimit);

        LocalDate creationDate = LocalDate.now();
        GeneratedCvv generatedCvv = cvvService.generateCvv();

        Card card = new Card();
        card.setCardNumber(cardNumberGenerator.generateCardNumber(cardBrand));
        card.setCardType(CardType.DEBIT);
        card.setCardName(cardBrand.toCardName());
        card.setCreationDate(creationDate);
        card.setExpirationDate(creationDate.plusYears(5));
        card.setAccountNumber(accountNumber.strip());
        card.setCvv(generatedCvv.hashedCvv());
        card.setCardLimit(cardLimit);
        card.setStatus(CardStatus.ACTIVE);

        Card savedCard = cardRepository.save(card);
        return new CardCreationResult(savedCard, generatedCvv.plainCvv());
    }

    /**
     * Validates externally supplied creation arguments before any generation work starts.
     * Validation rules are simple:
     * account number must not be blank,
     * and card limit must be present and must not be negative.
     *
     * @param accountNumber linked account number
     * @param cardLimit requested card limit
     */
    private void validateInput(String accountNumber, BigDecimal cardLimit) {
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ACCOUNT_NUMBER, "Account number must not be blank.");
        }
        if (cardLimit == null || cardLimit.signum() < 0) {
            throw new BusinessException(ErrorCode.INVALID_CARD_LIMIT, "Card limit must be zero or greater.");
        }
    }
}
