package com.banka1.card_service.controller;

import com.banka1.card_service.dto.card_creation.request.AutoCardCreationRequestDto;
import com.banka1.card_service.dto.card_creation.request.BusinessCardRequestDto;
import com.banka1.card_service.dto.card_creation.request.ClientCardRequestDto;
import com.banka1.card_service.dto.card_creation.response.CardCreationResponseDto;
import com.banka1.card_service.dto.card_creation.response.CardRequestResponseDto;
import com.banka1.card_service.rest_client.AccountNotificationContextDto;
import com.banka1.card_service.rest_client.AccountService;
import com.banka1.card_service.service.CardRequestService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Card creation API for automatic account flows and client-initiated requests.
 *
 * <p>The gateway owns the external {@code /api/cards/...} prefix.
 * Internally the service only exposes the route suffixes so the same external path can be shared by clients
 * and internal service callers without duplicating controller mappings.
 */
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardCreationController {

    private final CardRequestService cardRequestService;
    private final CardControllerSupport controllerSupport;
    private final AccountService accountService;

    /**
     * A card is AUTOMATICALLY created for the user, when the user account has been CREATED.
     *
     * @param body internal request payload
     * @return created card, including the stable card ID for follow-up management calls
     */
    @PostMapping("/auto")
    @Operation(summary = "Automatically create a debit card for a newly created account")
    @PreAuthorize("hasAnyRole('SERVICE', 'ADMIN')")
    public ResponseEntity<?> autoCreateCard(@RequestBody @Valid AutoCardCreationRequestDto body) {
        CardCreationResponseDto response = cardRequestService.createAutomaticCard(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Creates a personal card after the client already completed verification in verification-service.
     *
     * @param jwt JWT of the authenticated client
     * @param body request payload including {@code verificationId}
     * @return created card response with the new card ID and one-time sensitive card data
     */
    @PostMapping("/request")
    @Operation(summary = "Create a personal debit card for a verified personal account")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'ADMIN')")
    public ResponseEntity<CardRequestResponseDto> requestBasicCard(
            Authentication authentication,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid ClientCardRequestDto body
    ) {
        AccountNotificationContextDto accountContext = loadAccountContext(body.getAccountNumber());
        verifyOwnershipIfClient(authentication, jwt, accountContext);
        CardRequestResponseDto response = cardRequestService.processManualCardRequest(accountContext, body);
        return ResponseEntity.status(resolveRequestStatus(response)).body(response);
    }

    /**
     * Creates a business-account card for the owner or an authorized person after external verification.
     *
     * @param jwt JWT of the authenticated client
     * @param body request payload including {@code verificationId}
     * @return created card response with the new card ID and one-time sensitive card data
     */
    @PostMapping("/request/business")
    @Operation(summary = "Create a business debit card for the owner or an authorized person")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'ADMIN')")
    public ResponseEntity<CardRequestResponseDto> requestBusinessCard(
            Authentication authentication,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid BusinessCardRequestDto body
    ) {
        AccountNotificationContextDto accountContext = loadAccountContext(body.getAccountNumber());
        verifyOwnershipIfClient(authentication, jwt, accountContext);
        CardRequestResponseDto response = cardRequestService.processBusinessCardRequest(accountContext, body);
        return ResponseEntity.status(resolveRequestStatus(response)).body(response);
    }

    private HttpStatus resolveRequestStatus(CardRequestResponseDto response) {
        return response.createdCard() == null ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
    }

    private AccountNotificationContextDto loadAccountContext(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return new AccountNotificationContextDto(null, null);
        }
        return accountService.getAccountContext(accountNumber.strip());
    }

    private void verifyOwnershipIfClient(
            Authentication authentication,
            Jwt jwt,
            AccountNotificationContextDto accountContext
    ) {
        if (accountContext.ownerClientId() != null) {
            controllerSupport.verifyOwnershipIfClient(
                    authentication,
                    jwt,
                    accountContext.ownerClientId(),
                    "You do not own this account."
            );
        }
    }
}
