package com.banka1.bankingcore.interbank.controller;

import com.banka1.account_service.domain.Account;
import com.banka1.account_service.domain.SystemAccountIds;
import com.banka1.account_service.repository.AccountRepository;
import com.banka1.bankingcore.interbank.service.InterbankReservationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * REST endpoint-i koje interbank-service zove preko {@code BankingCoreInternalClient}
 * (PR_32 Phase 5/11, Tim 2 §4.6, §9.2).
 *
 * <p>Autorizacija: {@code hasRole('SERVICE')} — sluzbeni JWT iz interbank-service-a.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /internal/interbank/reserve-monas} — rezervise iznos na lokalnom
 *       racunu, vraca {@code reservationId} (UUID).</li>
 *   <li>{@code POST /internal/interbank/reservations/{id}/commit-monas} — commit 2PC
 *       faza, smanjuje full balance.</li>
 *   <li>{@code DELETE /internal/interbank/reservations/{id}} — release 2PC
 *       kompenzacija, vraca raspolozivo stanje.</li>
 *   <li>{@code GET /internal/interbank/account-resolve?num=...} — prevodi broj
 *       racuna u {ownerType, ownerId, currency, availableBalance}; koristi se za
 *       NO_SUCH_ACCOUNT i INSUFFICIENT_ASSET pre-validation u interbank-u.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/internal/interbank")
@PreAuthorize("hasRole('SERVICE')")
@RequiredArgsConstructor
public class InterbankReservationController {

    private final InterbankReservationService reservationService;
    private final AccountRepository accountRepository;

    public record ReserveMonasReq(
            @NotBlank String accountNum,
            @NotBlank String currency,
            @NotNull @Positive BigDecimal amount,
            int transactionIdRouting,
            @NotBlank String transactionIdLocal
    ) {}

    public record ReserveMonasRes(UUID reservationId) {}

    public record AccountResolveRes(
            String ownerType,
            Long ownerId,
            String currency,
            BigDecimal availableBalance
    ) {}

    @PostMapping("/reserve-monas")
    public ResponseEntity<ReserveMonasRes> reserveMonas(@RequestBody ReserveMonasReq req) {
        UUID id = reservationService.reserveMonas(
                req.accountNum(),
                req.currency(),
                req.amount(),
                req.transactionIdRouting(),
                req.transactionIdLocal());
        return ResponseEntity.ok(new ReserveMonasRes(id));
    }

    @PostMapping("/reservations/{id}/commit-monas")
    public ResponseEntity<Void> commit(@PathVariable UUID id) {
        reservationService.commitReservation(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/reservations/{id}")
    public ResponseEntity<Void> release(@PathVariable UUID id) {
        reservationService.releaseReservation(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/account-resolve")
    public ResponseEntity<AccountResolveRes> resolveAccount(@RequestParam("num") String accountNum) {
        Account account = accountRepository.findByBrojRacuna(accountNum)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + accountNum));

        Long vlasnik = account.getVlasnik();
        String ownerType = resolveOwnerType(vlasnik);

        String currencyCode = account.getCurrency() != null
                && account.getCurrency().getOznaka() != null
                ? account.getCurrency().getOznaka().name()
                : null;

        BigDecimal available = account.getRaspolozivoStanje() != null
                ? account.getRaspolozivoStanje()
                : BigDecimal.ZERO;

        return ResponseEntity.ok(new AccountResolveRes(
                ownerType, vlasnik, currencyCode, available));
    }

    /**
     * Maps {@code vlasnik} u {ownerType} kategoriju koju Tim 2 protokol koristi
     * za NO_SUCH_ACCOUNT / INSUFFICIENT_ASSET diferencijaciju.
     */
    private String resolveOwnerType(Long vlasnik) {
        if (vlasnik == null) {
            return "UNKNOWN";
        }
        if (vlasnik == SystemAccountIds.BANK) {
            return "BANK";
        }
        if (vlasnik == SystemAccountIds.STATE) {
            return "STATE";
        }
        if (vlasnik == SystemAccountIds.EXCHANGE) {
            return "EXCHANGE";
        }
        return "CLIENT";
    }
}
