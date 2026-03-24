package com.banka1.card_service.controller;

import com.banka1.card_service.dto.CardDetailDTO;
import com.banka1.card_service.dto.CardSummaryDTO;
import com.banka1.card_service.dto.UpdateCardLimitDTO;
import com.banka1.card_service.exception.BusinessException;
import com.banka1.card_service.exception.ErrorCode;
import com.banka1.card_service.service.CardLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for client card management.
 * Clients can view their own cards and block their own cards.
 * All endpoints require the CLIENT_BASIC role.
 */
@RestController
@RequestMapping("/api/cards")
@PreAuthorize("hasRole('CLIENT_BASIC')")
@RequiredArgsConstructor
public class ClientCardController {

    private final CardLifecycleService cardLifecycleService;

    @Value("${banka.security.id}")
    private String jwtIdClaim;

    /**
     * Returns all cards owned by the authenticated client.
     * Card numbers in the response are masked.
     * The {clientId} in the path must match the authenticated client's ID.
     *
     * @param jwt JWT of the authenticated client
     * @param clientId client ID path variable
     * @return list of masked card summaries
     */
    @GetMapping("/client/{clientId}")
    public ResponseEntity<List<CardSummaryDTO>> getCardsForClient(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long clientId
    ) {
        verifyOwnership(jwt, clientId);
        return ResponseEntity.ok(cardLifecycleService.getCardsForClient(clientId));
    }

    /**
     * Returns full details for a single card identified by card number.
     * The card must belong to the authenticated client.
     *
     * @param jwt JWT of the authenticated client
     * @param cardNumber card number to look up
     * @return full card details
     */
    @GetMapping("/{cardNumber}")
    public ResponseEntity<CardDetailDTO> getCardDetails(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String cardNumber
    ) {
        verifyOwnership(jwt, cardLifecycleService.getClientIdByCardNumber(cardNumber));
        return ResponseEntity.ok(cardLifecycleService.getCardByCardNumber(cardNumber));
    }

    /**
     * Blocks the card identified by card number.
     * The card must belong to the authenticated client.
     * Allowed transition: ACTIVE → BLOCKED.
     *
     * @param jwt JWT of the authenticated client
     * @param cardNumber card number to block
     * @return 200 OK on success
     */
    @PutMapping("/{cardNumber}/block")
    public ResponseEntity<Void> blockCard(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String cardNumber
    ) {
        verifyOwnership(jwt, cardLifecycleService.getClientIdByCardNumber(cardNumber));
        cardLifecycleService.blockCard(cardNumber);
        return ResponseEntity.ok().build();
    }

    /**
     * Updates the spending limit on a card.
     * The card must belong to the authenticated client.
     *
     * @param jwt JWT of the authenticated client
     * @param cardNumber card number to update
     * @param body request body containing the new limit
     * @return 200 OK on success
     */
    @PutMapping("/{cardNumber}/limit")
    public ResponseEntity<Void> updateCardLimit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String cardNumber,
            @RequestBody @Valid UpdateCardLimitDTO body
    ) {
        verifyOwnership(jwt, cardLifecycleService.getClientIdByCardNumber(cardNumber));
        cardLifecycleService.updateCardLimit(cardNumber, body.getCardLimit());
        return ResponseEntity.ok().build();
    }

    private void verifyOwnership(Jwt jwt, Long cardClientId) {
        Long requestingClientId = ((Number) jwt.getClaim(jwtIdClaim)).longValue();
        if (!requestingClientId.equals(cardClientId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "You do not own this card.");
        }
    }
}
