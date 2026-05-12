package com.banka1.bankingcore.transaction.controller.margin;

import com.banka1.bankingcore.transaction.dto.margin.MarginTransactionHistoryItemDto;
import com.banka1.bankingcore.transaction.dto.margin.MarginTransferDto;
import com.banka1.bankingcore.transaction.dto.margin.StockMarginTransactionDto;
import com.banka1.bankingcore.transaction.service.margin.MarginTransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST kontroler za margin trading transakcije (PR_03 C3.5).
 * Spec: Marzni_Racuni.txt — Upravljanje sredstvima Marznog racuna.
 */
@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class MarginTransactionController {

    private final MarginTransactionService marginTransactionService;

    /** Spec: POST /transactions/stockBuyMarginTransaction. */
    @PostMapping("/stockBuyMarginTransaction")
    public ResponseEntity<Void> buyOnMargin(@RequestBody @Valid StockMarginTransactionDto dto) {
        marginTransactionService.buyOnMargin(dto);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /** Spec: POST /transactions/stockSellMarginTransaction. */
    @PostMapping("/stockSellMarginTransaction")
    public ResponseEntity<Void> sellOnMargin(@RequestBody @Valid StockMarginTransactionDto dto) {
        marginTransactionService.sellOnMargin(dto);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /** Spec: POST /transactions/addToMargin/{userId}. */
    @PostMapping("/addToMargin/{userId}")
    public ResponseEntity<Void> addToMargin(
            @PathVariable Long userId,
            @RequestBody @Valid MarginTransferDto dto) {
        marginTransactionService.addToMarginForUser(userId, dto);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /** Spec: POST /transactions/withdrawFromMargin/{userId}. */
    @PostMapping("/withdrawFromMargin/{userId}")
    public ResponseEntity<Void> withdrawFromMargin(
            @PathVariable Long userId,
            @RequestBody @Valid MarginTransferDto dto) {
        marginTransactionService.withdrawFromMarginForUser(userId, dto);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    /**
     * Spec: GET /transactions/getAllMarginTransactions/{accountNumber}.
     * "Vraca i placanja i primanja novca" — sortirano po vremenu desc.
     */
    @GetMapping("/getAllMarginTransactions/{accountNumber}")
    public ResponseEntity<List<MarginTransactionHistoryItemDto>> getAllMarginTransactions(
            @PathVariable String accountNumber) {
        return ResponseEntity.ok(marginTransactionService.getAllForAccountNumber(accountNumber));
    }
}
