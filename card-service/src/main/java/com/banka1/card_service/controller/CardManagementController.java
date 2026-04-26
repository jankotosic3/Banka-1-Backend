package com.banka1.card_service.controller;

import com.banka1.card_service.dto.card_management.request.UpdateCardLimitDTO;
import com.banka1.card_service.dto.card_management.response.CardDetailDTO;
import com.banka1.card_service.dto.card_management.response.CardInternalSummaryDTO;
import com.banka1.card_service.dto.card_management.response.CardSummaryDTO;
import com.banka1.card_service.service.CardLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Card lifecycle management API exposed by the service behind the API gateway.
 *
 * <p>The gateway owns the external {@code /api/cards/...} prefix. Internally the service
 * only exposes the route suffixes so the same external path can be shared by clients
 * and employees without duplicating controller mappings.
 *
 * <p>Client and employee access rules are enforced at method level:
 * clients may list, inspect, block, and update limits only for their own cards,
 * while employees may inspect any card, list cards by account, and perform
 * administrative lifecycle transitions such as unblock and deactivate.
 */
@RestController
@RequiredArgsConstructor
public class CardManagementController {

    private final CardLifecycleService cardLifecycleService;
    private final CardControllerSupport controllerSupport;

    // ############################################################
    // CLIENT ENDPOINTS
    // Includes client-owned routes and shared routes clients can use
    // ############################################################

    /**
     * Returns all cards owned by the authenticated client.
     * Card numbers in the response are masked, while card IDs stay visible
     * so clients can fetch the full details of a specific card later.
     * The {@code clientId} in the path must match the authenticated client's ID.

     * Employee can see anybody's cards;
     * Clients can see only their own.
     *
     * @param jwt JWT of the authenticated client
     * @param clientId client ID path variable
     * @return list of masked card summaries
     */
    @GetMapping("/client/{clientId}")
    @Operation(summary = "List all cards for a client across their accounts")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC')")
    public ResponseEntity<List<CardSummaryDTO>> getCardsForClient(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable Long clientId
    ) {
        if (controllerSupport.isClient(authentication)) {
            controllerSupport.verifyOwnership(jwt, clientId);
        }
        return ResponseEntity.ok(cardLifecycleService.getCardsForClient(clientId));
    }

    /**
     * Returns full details for a single card identified by card ID.
     * Clients may access only their own cards, while employees may access any card.
     *
     * @param jwt JWT of the authenticated caller
     * @param authentication current Spring Security authentication
     * @param cardId card ID to look up
     * @return full card details
     */
    @GetMapping("/id/{cardId}")
    @Operation(summary = "Get full card details by card ID")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC')")
    public ResponseEntity<CardDetailDTO> getCardDetails(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable Long cardId
    ) {
        verifyOwnershipIfClient(authentication, jwt, cardId);
        return ResponseEntity.ok(cardLifecycleService.getCardById(cardId));
    }

    /**
     * Blocks the card identified by card ID.
     * Clients may block only their own cards, while employees may block any card.
     * Allowed transition: ACTIVE {@code ->} BLOCKED.
     *
     * @param jwt JWT of the authenticated caller
     * @param authentication current Spring Security authentication
     * @param cardId card ID to block
     * @return 200 OK on success
     */
    @PutMapping("/id/{cardId}/block")
    @Operation(summary = "Block a card by card ID")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC')")
    public ResponseEntity<Void> blockCard(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable Long cardId
    ) {
        verifyOwnershipIfClient(authentication, jwt, cardId);
        cardLifecycleService.blockCard(cardId);
        return ResponseEntity.ok().build();
    }

    /**
     * Updates the spending limit on an existing card.
     * Clients may update only their own cards, while employees may update any card.
     *
     * @param jwt JWT of the authenticated caller
     * @param authentication current Spring Security authentication
     * @param cardId card ID to update
     * @param body request body containing the new limit
     * @return 200 OK on success
     */
    @PutMapping("/id/{cardId}/limit")
    @Operation(summary = "Update a card spending limit by card ID")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC')")
    public ResponseEntity<Void> updateCardLimit(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable Long cardId,
            @RequestBody @Valid UpdateCardLimitDTO body
    ) {
        verifyOwnershipIfClient(authentication, jwt, cardId);
        cardLifecycleService.updateCardLimit(cardId, body.getCardLimit());
        return ResponseEntity.ok().build();
    }

    // ############################################################
    // EMPLOYEE ENDPOINTS
    // Administrative routes available only to employee roles
    // ############################################################

    /**
     * Returns all cards associated with the given bank account number.
     * Card numbers in the response are masked, while card IDs stay visible.
     * This endpoint is available only to employees.
     *
     * @param accountNumber bank account number
     * @return list of masked card summaries
     */
    @GetMapping("/account/{accountNumber}")
    @Operation(summary = "List all cards for a specific account number")
    @PreAuthorize("hasRole('BASIC')")
    public ResponseEntity<List<CardSummaryDTO>> getCardsByAccount(@PathVariable String accountNumber) {
        return ResponseEntity.ok(cardLifecycleService.getCardsByAccountNumber(accountNumber));
    }

    // ############################################################
    // INTERNAL SERVICE ENDPOINTS
    // Restricted to trusted service-to-service callers (SERVICE role)
    // ############################################################

    /**
     * Returns all cards linked to the given account number in the richer internal shape
     * consumed by account-service when it composes account detail responses.
     * Only trusted services authenticated with the {@code SERVICE} role may call this endpoint.
     * Card numbers in the response are masked.
     *
     * @param accountNumber bank account number
     * @return list of internal card summaries
     */
    @GetMapping("/internal/account/{accountNumber}")
    @Operation(summary = "List masked card summaries for an account for internal service use")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<List<CardInternalSummaryDTO>> getCardsByAccountInternal(@PathVariable String accountNumber) {
        return ResponseEntity.ok(cardLifecycleService.getInternalCardsByAccountNumber(accountNumber));
    }

    /**
     * Unblocks a blocked card.
     * Only employees may call this endpoint.
     * Allowed transition: BLOCKED {@code ->} ACTIVE.
     *
     * @param cardId card ID to unblock
     * @return 200 OK on success
     */
    @PutMapping("/id/{cardId}/unblock")
    @Operation(summary = "Unblock a card by card ID")
    @PreAuthorize("hasRole('BASIC')")
    public ResponseEntity<Void> unblockCard(@PathVariable Long cardId) {
        cardLifecycleService.unblockCard(cardId);
        return ResponseEntity.ok().build();
    }

    /**
     * Permanently deactivates a card.
     * Only employees may call this endpoint.
     * Allowed transitions: ACTIVE {@code ->} DEACTIVATED or BLOCKED {@code ->} DEACTIVATED.
     * Deactivation is irreversible.
     *
     * @param cardId card ID to deactivate
     * @return 200 OK on success
     */
    @PutMapping("/id/{cardId}/deactivate")
    @Operation(summary = "Deactivate a card by card ID")
    @PreAuthorize("hasRole('BASIC')")
    public ResponseEntity<Void> deactivateCard(@PathVariable Long cardId) {
        cardLifecycleService.deactivateCard(cardId);
        return ResponseEntity.ok().build();
    }

    private void verifyOwnershipIfClient(Authentication authentication, Jwt jwt, Long cardId) {
        if (controllerSupport.isClient(authentication)) {
            controllerSupport.verifyOwnership(
                    jwt,
                    cardLifecycleService.getClientIdByCardId(cardId),
                    "You do not own this card."
            );
        }
    }
}
