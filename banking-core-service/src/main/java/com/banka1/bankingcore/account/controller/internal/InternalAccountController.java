package com.banka1.bankingcore.account.controller.internal;

import com.banka1.bankingcore.account.client.AccountServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoint-i koje SAGA orchestrator poziva preko
 * {@link com.banka1.bankingcore.account.client.AccountServiceClient}-a paralelnog
 * iz svoje strane (PR_14 C14.6).
 *
 * <p>Pre PR_14 {@code BankingCoreClient.resolveDefaultAccountNumber} je gadjao
 * banking-core na 404 — endpoint nije postojao. SAGA bi se srusila u OTC_EXERCISE
 * step 3.
 */
@RestController
@RequestMapping("/accounts/internal")
@RequiredArgsConstructor
public class InternalAccountController {

    private final ObjectProvider<AccountServiceClient> accountServiceClientProvider;

    @GetMapping("/default/{ownerId}")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Map<String, String>> defaultAccount(@PathVariable Long ownerId) {
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("AccountServiceClient nije dostupan.");
        }
        String accountNumber = client.findClientAccounts(ownerId).stream()
                .filter(a -> "RSD".equals(a.currency()))
                .map(AccountServiceClient.AccountDetails::accountNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Klijent " + ownerId + " nema RSD tekuci racun za default lookup."));
        return ResponseEntity.ok(Map.of("ownerId", String.valueOf(ownerId), "accountNumber", accountNumber));
    }
}
