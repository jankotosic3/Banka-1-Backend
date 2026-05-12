package com.banka1.bankingcore.account.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * REST klijent ka account-service-u (PR_14 C14.4).
 *
 * <p>Banking-core nema lokalnu accounts/transactions tabelu (banking_core DB sadrzi
 * samo margin_accounts + log tabele); zato se sve operacije nad tekucim racunima
 * delegiraju account-service-u kroz {@code /internal/accounts/*} endpoint-e.
 *
 * <p>Koristi se iz:
 * <ul>
 *   <li>{@code MarginTransactionService.addToMarginForUser} — debit klijentskog
 *       tekuceg racuna prilikom uplate na marzni.</li>
 *   <li>{@code MarginTransactionService.withdrawFromMarginForUser} — credit klijentskog
 *       tekuceg racuna prilikom isplate sa marznog.</li>
 *   <li>{@code BankToExchangeTransferService} — bank-to-exchange i obrnuti smer
 *       (PR_14 C14.6 prelazak sa lokalne JPA na REST).</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("!local")
@RequiredArgsConstructor
public class AccountServiceClient {

    private final RestClient accountRestClient;

    /**
     * Skida {@code amount} sa racuna {@code accountNumber}. clientId je obavezan jer
     * account-service validira da racun pripada klijentu.
     *
     * @throws org.springframework.web.client.RestClientResponseException ako racun ne
     *         postoji, balans je nedovoljan ili clientId ne odgovara vlasniku.
     */
    public void debit(String accountNumber, BigDecimal amount, Long clientId) {
        accountRestClient.post()
                .uri("/internal/accounts/debit")
                .body(Map.of(
                        "accountNumber", accountNumber,
                        "amount", amount,
                        "clientId", clientId
                ))
                .retrieve()
                .toBodilessEntity();
        log.info("Debit ka account-service: account={} amount={} clientId={}", accountNumber, amount, clientId);
    }

    /**
     * Dodaje {@code amount} na racun {@code accountNumber}.
     */
    public void credit(String accountNumber, BigDecimal amount, Long clientId) {
        accountRestClient.post()
                .uri("/internal/accounts/credit")
                .body(Map.of(
                        "accountNumber", accountNumber,
                        "amount", amount,
                        "clientId", clientId
                ))
                .retrieve()
                .toBodilessEntity();
        log.info("Credit ka account-service: account={} amount={} clientId={}", accountNumber, amount, clientId);
    }

    /**
     * Vraca account po ID/account-number kombinaciji. Rezultat je {@link AccountDetails}
     * (slim DTO sa poljima koje banking-core potrebuje — ownerId, currency, balance).
     */
    public AccountDetails getById(Long accountId) {
        return accountRestClient.get()
                .uri("/internal/accounts/id/{accountId}/details", accountId)
                .retrieve()
                .body(AccountDetails.class);
    }

    public AccountDetails getByNumber(String accountNumber) {
        return accountRestClient.get()
                .uri("/internal/accounts/{accountNumber}/details", accountNumber)
                .retrieve()
                .body(AccountDetails.class);
    }

    public AccountDetails getBankAccount(String currencyCode) {
        return accountRestClient.get()
                .uri("/internal/accounts/bank/{currencyCode}", currencyCode)
                .retrieve()
                .body(AccountDetails.class);
    }

    /**
     * Vraca racune klijenta. Koristi se da se nadje RSD tekuci racun klijenta kada
     * MarginTransferDto ne nosi explicitni fromAccountNumber (spec
     * Marzni_Racuni.txt definise samo {userId, amount}).
     *
     * @return lista racuna; banking-core filter-uje po currency=RSD i type=PERSONAL/BUSINESS.
     */
    public List<AccountDetails> findClientAccounts(Long clientId) {
        ClientAccountsPage page = accountRestClient.get()
                .uri("/employee/accounts/client/{clientId}?page=0&size=100", clientId)
                .retrieve()
                .body(ClientAccountsPage.class);
        return page != null && page.content() != null ? page.content() : List.of();
    }

    /**
     * DTO za /internal/accounts response. Sadrzi samo polja koje banking-core
     * koristi; deserializator ignorise ostala polja iz account-service odgovora.
     */
    public record AccountDetails(
            Long id,
            String accountNumber,
            Long ownerId,
            String currency,
            BigDecimal availableBalance,
            String status,
            String accountType
    ) {}

    /**
     * Spring Data Page wrapper. Account-service vraca {@code Page<AccountResponseDto>}
     * sa standardnim Spring Data Page poljima — ovde nas zanima samo {@code content}.
     */
    public record ClientAccountsPage(List<AccountDetails> content) {}
}
