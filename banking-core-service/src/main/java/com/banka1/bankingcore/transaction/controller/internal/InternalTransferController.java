package com.banka1.bankingcore.transaction.controller.internal;

import com.banka1.bankingcore.transaction.service.internal.InternalTransferService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * REST endpoint-i za interne transfere koje SAGA orchestrator inicira
 * (PR_14 C14.6 — zatvara rupu iz PR_11/PR_12 gde su ovi endpoint-i postojali
 * samo u {@code BankingCoreClient}-u sa orchestrator strane).
 */
@RestController
@RequestMapping("/transactions/internal")
@RequiredArgsConstructor
public class InternalTransferController {

    private final InternalTransferService transferService;

    @PostMapping("/transfer")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<InternalTransferService.Transfer> transfer(
            @RequestBody TransferRequest req,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return ResponseEntity.ok(transferService.transfer(
                req.getFromAccountNumber(),
                req.getToAccountNumber(),
                req.getAmount(),
                correlationId != null ? correlationId : "no-correlation"));
    }

    @PostMapping("/transfers/{id}/reverse")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<InternalTransferService.Transfer> reverse(
            @PathVariable("id") String transferId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return ResponseEntity.ok(transferService.reverse(
                transferId,
                correlationId != null ? correlationId : "no-correlation"));
    }

    @Data
    public static class TransferRequest {
        @NotBlank
        private String fromAccountNumber;
        @NotBlank
        private String toAccountNumber;
        @NotNull
        private BigDecimal amount;
    }
}
