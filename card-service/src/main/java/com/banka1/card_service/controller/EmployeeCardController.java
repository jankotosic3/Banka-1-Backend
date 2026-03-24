package com.banka1.card_service.controller;

import com.banka1.card_service.dto.CardDetailDTO;
import com.banka1.card_service.dto.CardSummaryDTO;
import com.banka1.card_service.dto.UpdateCardLimitDTO;
import com.banka1.card_service.service.CardLifecycleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for employee card management.
 * Employees can view cards for any account, block, unblock, and permanently deactivate cards.
 * All endpoints require the EMPLOYEE role.
 */
@RestController
@RequestMapping("/api/cards")
@PreAuthorize("hasRole('EMPLOYEE')")
@RequiredArgsConstructor
public class EmployeeCardController {

    private final CardLifecycleService cardLifecycleService;

    /**
     * Returns all cards associated with the given bank account number.
     * Card numbers in the response are masked.
     *
     * @param accountNumber bank account number
     * @return list of masked card summaries
     */
    @GetMapping("/account/{accountNumber}")
    public ResponseEntity<List<CardSummaryDTO>> getCardsByAccount(@PathVariable String accountNumber) {
        return ResponseEntity.ok(cardLifecycleService.getCardsByAccountNumber(accountNumber));
    }

    /**
     * Returns full details for the card identified by the given card number.
     *
     * @param cardNumber card number
     * @return full card details
     */
    @GetMapping("/{cardNumber}")
    public ResponseEntity<CardDetailDTO> getCardDetails(@PathVariable String cardNumber) {
        return ResponseEntity.ok(cardLifecycleService.getCardByCardNumber(cardNumber));
    }

    /**
     * Blocks an active card.
     * Allowed transition: ACTIVE → BLOCKED.
     *
     * @param cardNumber card number to block
     * @return 200 OK on success
     */
    @PutMapping("/{cardNumber}/block")
    public ResponseEntity<Void> blockCard(@PathVariable String cardNumber) {
        cardLifecycleService.blockCard(cardNumber);
        return ResponseEntity.ok().build();
    }

    /**
     * Unblocks a blocked card. Only employees may unblock cards.
     * Allowed transition: BLOCKED → ACTIVE.
     *
     * @param cardNumber card number to unblock
     * @return 200 OK on success
     */
    @PutMapping("/{cardNumber}/unblock")
    public ResponseEntity<Void> unblockCard(@PathVariable String cardNumber) {
        cardLifecycleService.unblockCard(cardNumber);
        return ResponseEntity.ok().build();
    }

    /**
     * Permanently deactivates a card. Only employees may deactivate cards.
     * Deactivation is irreversible — a deactivated card cannot be reactivated.
     * Allowed transitions: ACTIVE → DEACTIVATED or BLOCKED → DEACTIVATED.
     *
     * @param cardNumber card number to deactivate
     * @return 200 OK on success
     */
    @PutMapping("/{cardNumber}/deactivate")
    public ResponseEntity<Void> deactivateCard(@PathVariable String cardNumber) {
        cardLifecycleService.deactivateCard(cardNumber);
        return ResponseEntity.ok().build();
    }

    /**
     * Updates the spending limit on an existing card.
     *
     * @param cardNumber card number to update
     * @param body request body containing the new limit
     * @return 200 OK on success
     */
    @PutMapping("/{cardNumber}/limit")
    public ResponseEntity<Void> updateCardLimit(
            @PathVariable String cardNumber,
            @RequestBody @Valid UpdateCardLimitDTO body
    ) {
        cardLifecycleService.updateCardLimit(cardNumber, body.getCardLimit());
        return ResponseEntity.ok().build();
    }
}
