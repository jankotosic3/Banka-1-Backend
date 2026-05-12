package com.banka1.bankingcore.transaction.service.margin;

import com.banka1.bankingcore.account.client.AccountServiceClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bank-to-Exchange transfer servis (PR_12 C12.2; PR_14 C14.4 prelazak na REST).
 *
 * <p>Spec (Marzni_Racuni.txt): pri buy-on-margin transakciji jedan deo (bankPart =
 * amount * bankParticipation) ide sa racuna banke ka racunu berze. Pri sell-on-margin
 * obrnuto.
 *
 * <p>Pre PR_14: ova klasa je referencirala lokalne {@code AccountRepository} i
 * {@code TransactionRepository} entitete koje banking-core nikad nije imao u svom
 * persistence unit-u (banking_core DB sadrzi samo margin_accounts + log tabele).
 * Klasa se nije ni kompilirala. Sada ide kroz {@link AccountServiceClient} koji
 * REST-om razgovara sa account-service-om gde su accounts/transactions tabele.
 *
 * <p>Bank account number i exchange account number se konfigurisu preko property-ja:
 * <pre>
 * banka.banking.bank-account-number=1234567812345670
 * banka.banking.exchange-account-number=9876543212345674
 * banka.banking.bank-client-id=-1
 * banka.banking.exchange-client-id=-3
 * </pre>
 *
 * <p>PR_29: Hardkodirani DEV default-i ({@code 111000110000000312},
 * {@code 111000300000002012}, {@code -3} kao exchange client id) ranije su sluzili kao
 * fallback ako env var nije postavljen — ali ti racuni ne postoje u prod bazi, sto bi
 * dovelo do silent fail-a. Sada @PostConstruct odbija da startuje servis ako property
 * vrednosti nedostaju ili sadrze ocigledne dev sentinel-ove (-3 client id), osim u
 * dev/local/test profilima gde je dev fallback eksplicitno dozvoljen.
 *
 * <p>PR_30: Brojevi racuna su preradjeni da prolaze spec mod-11 invariant
 * ((zbir cifara) % 11 == 0). Stari '1110001000000000012' (sum=7) i
 * '1110003000000000031' (sum=10) krsili su Celina 2 spec.
 */
@Slf4j
@Service
public class BankToExchangeTransferService {

    /** Profili u kojima je dozvoljen dev default. */
    private static final Set<String> NON_PROD_PROFILES = Set.of("dev", "local", "test");

    /** Hardkodirani DEV defaults. Drze se ovde da bi @PostConstruct mogao da
     * detektuje da li je vrednost iz env-a stvarno setovana ili je palo na default. */
    static final String DEV_DEFAULT_BANK_ACCOUNT = "111000110000000312";
    static final String DEV_DEFAULT_EXCHANGE_ACCOUNT = "111000300000002012";
    static final long DEV_DEFAULT_BANK_CLIENT_ID = -1L;
    static final long DEV_DEFAULT_EXCHANGE_CLIENT_ID = -3L;

    private final ObjectProvider<AccountServiceClient> accountServiceClientProvider;
    private final Environment springEnvironment;
    private final AtomicLong correlationCounter = new AtomicLong();

    @Value("${banka.banking.bank-account-number:" + DEV_DEFAULT_BANK_ACCOUNT + "}")
    private String bankAccountNumber;

    @Value("${banka.banking.exchange-account-number:" + DEV_DEFAULT_EXCHANGE_ACCOUNT + "}")
    private String exchangeAccountNumber;

    @Value("${banka.banking.bank-client-id:-1}")
    private Long bankClientId;

    @Value("${banka.banking.exchange-client-id:-3}")
    private Long exchangeClientId;

    public BankToExchangeTransferService(ObjectProvider<AccountServiceClient> accountServiceClientProvider,
                                         Environment springEnvironment) {
        this.accountServiceClientProvider = accountServiceClientProvider;
        this.springEnvironment = springEnvironment;
    }

    /**
     * Validira margin transfer konfiguraciju pri boot-u. U prod profilu zahteva da
     * sve cetiri vrednosti budu eksplicitno setovane preko env-a; u dev/local/test
     * dozvoljava DEV default-e.
     */
    @PostConstruct
    void validateConfiguration() {
        boolean nonProd = isNonProdProfile();
        boolean usingBankAccountDev = DEV_DEFAULT_BANK_ACCOUNT.equals(bankAccountNumber);
        boolean usingExchangeAccountDev = DEV_DEFAULT_EXCHANGE_ACCOUNT.equals(exchangeAccountNumber);
        boolean usingBankClientDev = bankClientId != null && bankClientId == DEV_DEFAULT_BANK_CLIENT_ID;
        boolean usingExchangeClientDev = exchangeClientId != null && exchangeClientId == DEV_DEFAULT_EXCHANGE_CLIENT_ID;

        if (!nonProd && (usingBankAccountDev || usingExchangeAccountDev || usingExchangeClientDev)) {
            throw new IllegalStateException(
                    "Margin bank/exchange account konfiguracija koristi DEV default vrednosti, a aktivni "
                            + "profili nisu dev/local/test. Postavi env vars: "
                            + "BANKA_BANKING_BANK_ACCOUNT_NUMBER, BANKA_BANKING_EXCHANGE_ACCOUNT_NUMBER, "
                            + "BANKA_BANKING_BANK_CLIENT_ID, BANKA_BANKING_EXCHANGE_CLIENT_ID. "
                            + "Bank-to-exchange margin transfer u protivnom bi pokusao da debituje "
                            + "racune koji ne postoje u prod bazi.");
        }
        if (bankAccountNumber == null || bankAccountNumber.isBlank()
                || exchangeAccountNumber == null || exchangeAccountNumber.isBlank()
                || bankClientId == null || exchangeClientId == null) {
            throw new IllegalStateException(
                    "Margin bank/exchange account konfiguracija ima null/blank vrednosti.");
        }
        log.info("Margin transfer config: bankAccount={} exchangeAccount={} bankClientId={} exchangeClientId={} (devFallback={})",
                bankAccountNumber, exchangeAccountNumber, bankClientId, exchangeClientId,
                usingBankAccountDev || usingExchangeAccountDev || usingBankClientDev || usingExchangeClientDev);
    }

    private boolean isNonProdProfile() {
        if (springEnvironment == null) return true;
        String[] active = springEnvironment.getActiveProfiles();
        if (active == null || active.length == 0) return true;
        return Arrays.stream(active).anyMatch(NON_PROD_PROFILES::contains);
    }

    /**
     * Salje sredstva sa bank account-a na exchange account (kupac kupuje hartije od berze).
     *
     * @return interni correlation id za audit trail (raste monotono u JVM-u). Pravi
     *         transaction id koji generise account-service nije izlozen kroz REST
     *         pa banking-core koristi sopstveni counter za logging korelaciju.
     * @throws IllegalStateException ako bank account nema dovoljno sredstava
     */
    @Transactional
    public Long sendToExchange(BigDecimal amount, String correlationId) {
        AccountServiceClient client = clientOrThrow();
        client.debit(bankAccountNumber, amount, bankClientId);
        client.credit(exchangeAccountNumber, amount, exchangeClientId);

        Long localTxId = correlationCounter.incrementAndGet();
        log.info("Bank-to-exchange transfer OK: amount={} correlationId={} localTxId={} occurredAt={}",
                amount, correlationId, localTxId, LocalDateTime.now());
        return localTxId;
    }

    /**
     * Salje sredstva sa exchange account-a na bank account (klijent prodaje hartije,
     * berza placa banci sto banka onda krediti klijentovog marznog racuna).
     */
    @Transactional
    public Long receiveFromExchange(BigDecimal amount, String correlationId) {
        AccountServiceClient client = clientOrThrow();
        client.debit(exchangeAccountNumber, amount, exchangeClientId);
        client.credit(bankAccountNumber, amount, bankClientId);

        Long localTxId = correlationCounter.incrementAndGet();
        log.info("Exchange-to-bank transfer OK: amount={} correlationId={} localTxId={} occurredAt={}",
                amount, correlationId, localTxId, LocalDateTime.now());
        return localTxId;
    }

    private AccountServiceClient clientOrThrow() {
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "AccountServiceClient nije dostupan (verovatno local profil) — bank-to-exchange "
                            + "transfer ne moze da se izvrsi. Postavi services.account.url i ukini local profil.");
        }
        return client;
    }
}
