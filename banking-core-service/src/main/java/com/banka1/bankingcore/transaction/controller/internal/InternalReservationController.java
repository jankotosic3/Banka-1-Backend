package com.banka1.bankingcore.transaction.controller.internal;

import com.banka1.bankingcore.transaction.service.internal.FundReservationService;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * REST endpoint-i koje SAGA orchestrator (saga-orchestrator-service)
 * poziva preko {@code BankingCoreClient.reserveFunds/releaseFunds} (PR_14 C14.6).
 *
 * <p>Podrazumevano se koristi Step 1 OTC_EXERCISE i Step 1 FUND_SUBSCRIBE
 * saga-e. Pre PR_14 ovi endpoint-i nisu postojali, pa su saga-e padale na
 * 404 odmah pri pokretanju.
 *
 * <p>Authorization: hasRole('SERVICE') — sluzbeni JWT iz saga-orchestrator-a.
 */
@RestController
@RequestMapping("/transactions/internal")
@RequiredArgsConstructor
public class InternalReservationController {

    private final FundReservationService reservationService;

    @PostMapping("/reserve-funds")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<FundReservationService.Reservation> reserve(
            @RequestBody ReserveRequest req,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return ResponseEntity.ok(reservationService.reserve(
                req.getOwnerId(),
                req.getAmount(),
                correlationId != null ? correlationId : "no-correlation"));
    }

    @DeleteMapping("/reservations/{id}")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<FundReservationService.Reservation> release(
            @PathVariable("id") String reservationId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return ResponseEntity.ok(reservationService.release(
                reservationId,
                correlationId != null ? correlationId : "no-correlation"));
    }

    @PostMapping("/reservations/{id}/commit")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<FundReservationService.Reservation> commit(
            @PathVariable("id") String reservationId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return ResponseEntity.ok(reservationService.commit(
                reservationId,
                correlationId != null ? correlationId : "no-correlation"));
    }

    @Data
    public static class ReserveRequest {
        @NotNull
        private Long ownerId;
        @NotNull
        private BigDecimal amount;
    }
}
