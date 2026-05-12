package com.banka1.bankingcore.transaction.service.margin;

import com.banka1.bankingcore.account.client.AccountServiceClient;
import com.banka1.bankingcore.account.domain.margin.MarginAccount;
import com.banka1.bankingcore.account.repository.margin.CompanyMarginAccountRepository;
import com.banka1.bankingcore.account.repository.margin.MarginAccountRepository;
import com.banka1.bankingcore.account.repository.margin.UserMarginAccountRepository;
import com.banka1.bankingcore.account.service.margin.MarginAccountService;
import com.banka1.bankingcore.transaction.domain.margin.MarginTransaction;
import com.banka1.bankingcore.transaction.domain.margin.MarginTransactionType;
import com.banka1.bankingcore.transaction.dto.margin.MarginTransactionHistoryItemDto;
import com.banka1.bankingcore.transaction.dto.margin.MarginTransferDto;
import com.banka1.bankingcore.transaction.dto.margin.StockMarginTransactionDto;
import com.banka1.bankingcore.transaction.repository.margin.MarginTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Servis za margin trading + uplate/isplate (PR_03 C3.5 + C3.6).
 *
 * <p>Spec: Marzni_Racuni.txt — Upravljanje sredstvima Marznog racuna.
 *
 * <p>Pessimistic-write lock je obavezan na hot path-u za buy/sell jer dva paralelna
 * order-a mogu pokusati istovremeno menjanje istog racuna; bez locka racun bi pao
 * u nekonzistentno stanje (lost update na initialMargin).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarginTransactionService {

    private final MarginAccountRepository marginAccountRepository;
    private final UserMarginAccountRepository userMarginAccountRepository;
    private final CompanyMarginAccountRepository companyMarginAccountRepository;
    private final MarginAccountService marginAccountService;
    private final MarginTransactionRepository marginTransactionRepository;
    private final BankToExchangeTransferService bankToExchangeTransferService;  // PR_12 C12.2
    /**
     * AccountServiceClient (PR_14 C14.4) — REST poziv ka account-service-u za debit/credit
     * tekuceg racuna. Wrap-uje se u {@link ObjectProvider} jer u local profilu bean ne
     * postoji (RestClientConfig je @Profile("!local")) — pa add/withdraw padaju nazad
     * na "samo evidentiranje na marznom" sto je prihvatljivo za H2 unit test ali ne i
     * za prod gde je tekuci racun obavezno za debit-ovati.
     */
    private final ObjectProvider<AccountServiceClient> accountServiceClientProvider;

    /**
     * Spec: stockBuyMarginTransaction.
     * <pre>
     * Pare skidamo iz dva dela.
     * Jedan deo: ukupna_vrednost * BankParticipation -> dodaje se na LoanValue marznog racuna
     *            i transakcija ide od racuna banke ka racunu berze (hardcoded delegacija
     *            postojecoj funkciji).
     * Drugi deo: ukupna_vrednost - (ukupna_vrednost * BankParticipation) -> skida se sa InitialMargin.
     * Ako InitialMargin nedovoljan -> cela transakcija ponistena.
     * </pre>
     */
    @Transactional
    public void buyOnMargin(StockMarginTransactionDto dto) {
        validateMutuallyExclusive(dto);

        MarginAccount account = lockAccountForOwner(dto.getUserId(), dto.getCompanyId());

        if (!account.isActive()) {
            throw new IllegalStateException(
                    "Marzni racun " + account.getAccountNumber() + " je blokiran (initialMargin ispod maintenanceMargin).");
        }

        BigDecimal amount = dto.getAmount();
        BigDecimal bankPart = amount.multiply(account.getBankParticipation()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal clientPart = amount.subtract(bankPart);

        if (account.getInitialMargin().compareTo(clientPart) < 0) {
            throw new IllegalStateException(
                    "Nedovoljno sredstava na initialMargin (potrebno: " + clientPart
                            + ", raspolozivo: " + account.getInitialMargin() + ").");
        }

        account.setInitialMargin(account.getInitialMargin().subtract(clientPart));
        account.setLoanValue(account.getLoanValue().add(bankPart));
        marginAccountService.recalcActive(account);

        // PR_12 C12.2: pravi bank-to-exchange transfer (umesto stub log-a).
        Long bankTransferId = bankToExchangeTransferService.sendToExchange(
                bankPart, "margin-buy-" + account.getAccountNumber() + "-" + System.currentTimeMillis());
        log.info("[stockBuyMargin] account={} amount={} bankPart={} clientPart={} bankTxId={}",
                account.getAccountNumber(), amount, bankPart, clientPart, bankTransferId);

        marginAccountRepository.save(account);
        recordTransaction(account, amount.negate(), MarginTransactionType.STOCK_BUY,
                "Buy on margin: bank=" + bankPart + " client=" + clientPart + " bankTxId=" + bankTransferId);
    }

    /**
     * Spec: stockSellMarginTransaction.
     * <pre>
     * Pare dodajemo na marzni racun (od berze ka banci).
     * Prvo skidamo dug:
     *   ukupna_vrednost * BankParticipation -> skida se sa LoanValue (i prebacuje na racun banke).
     * Ako LoanValue ide ispod 0, ostavi se na 0 (vise ne placamo banci).
     * Drugi deo: ukupna_vrednost - (ukupna_vrednost * BankParticipation) -> dodaje se na InitialMargin.
     * </pre>
     */
    @Transactional
    public void sellOnMargin(StockMarginTransactionDto dto) {
        validateMutuallyExclusive(dto);

        MarginAccount account = lockAccountForOwner(dto.getUserId(), dto.getCompanyId());

        if (!account.isActive()) {
            throw new IllegalStateException(
                    "Marzni racun " + account.getAccountNumber() + " je blokiran.");
        }

        BigDecimal amount = dto.getAmount();
        BigDecimal bankPart = amount.multiply(account.getBankParticipation()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal clientPart = amount.subtract(bankPart);

        // Loan ide ka 0; ako je manji od bankPart, sve ostalo ide na initialMargin.
        BigDecimal loanReduction = account.getLoanValue().min(bankPart);
        BigDecimal clientCredit = clientPart.add(bankPart.subtract(loanReduction));

        account.setLoanValue(account.getLoanValue().subtract(loanReduction));
        account.setInitialMargin(account.getInitialMargin().add(clientCredit));
        marginAccountService.recalcActive(account);

        // PR_12 C12.2: pravi exchange-to-bank transfer (umesto stub log-a).
        Long bankTransferId = null;
        if (loanReduction.compareTo(BigDecimal.ZERO) > 0) {
            bankTransferId = bankToExchangeTransferService.receiveFromExchange(
                    loanReduction, "margin-sell-" + account.getAccountNumber() + "-" + System.currentTimeMillis());
        }
        log.info("[stockSellMargin] account={} amount={} loanReduction={} clientCredit={} bankTxId={}",
                account.getAccountNumber(), amount, loanReduction, clientCredit, bankTransferId);

        marginAccountRepository.save(account);
        recordTransaction(account, amount, MarginTransactionType.STOCK_SELL,
                "Sell on margin: loan-reduction=" + loanReduction + " client-credit=" + clientCredit
                        + " bankTxId=" + bankTransferId);
    }

    /**
     * Spec: addToMargin/{userId}. Sa tekuceg racuna uplacujemo pare na marzni.
     *
     * <p>PR_14 C14.4: pravo skidanje sa tekuceg racuna preko {@link AccountServiceClient}.
     * Pre PR_14 metoda je samo evidentirala dodatak na marznom (komentar je tvrdio
     * "tekuci se skida kroz postojeci AccountService.debit", ali poziva nije bilo).
     * Sada: prvo debit klijentskog tekuceg → onda dodavanje na marzni. Ako debit ne
     * uspe (insuficijentno stanje, racun ne postoji), {@code RestClientResponseException}
     * se propaga gore i transakcija se rollback-uje (margin nije diran).
     *
     * <p>Tekuci racun se bira iz dto.fromAccountNumber-a; ako je null, banking-core
     * trazi prvi RSD racun klijenta preko {@code findClientAccounts}.
     */
    @Transactional
    public void addToMarginForUser(Long userId, MarginTransferDto dto) {
        MarginAccount account = userMarginAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Marzni racun za userId=" + userId + " ne postoji."));

        String fromAccount = resolveCheckingAccountNumber(userId, dto.getFromAccountNumber());
        debitChecking(fromAccount, dto.getAmount(), userId);

        account.setInitialMargin(account.getInitialMargin().add(dto.getAmount()));
        marginAccountService.recalcActive(account);  // moze da ode iznad maintenance, da odblokira
        marginAccountRepository.save(account);

        log.info("[addToMargin] userId={} amount={} from={} initialMargin={} active={}",
                userId, dto.getAmount(), fromAccount, account.getInitialMargin(), account.isActive());
        recordTransaction(account, dto.getAmount(), MarginTransactionType.ADD_TO_MARGIN,
                "Transfer from checking " + fromAccount + " to margin");
    }

    /**
     * Spec: withdrawFromMargin/{userId}. Sa marznog na tekuci.
     *
     * <p>PR_14 C14.4: pravo dodavanje na klijentski tekuci racun preko
     * {@link AccountServiceClient}. Smanjuje initialMargin pre nego sto credit-uje
     * tekuci, ali ako credit fail-uje, transactional rollback vraca initialMargin
     * u prethodnu vrednost — net effect: ili oboje uspe ili nista.
     *
     * <p>Ne dozvoli ako je racun blokiran ili ako bi initialMargin pao ispod
     * maintenanceMargin.
     */
    @Transactional
    public void withdrawFromMarginForUser(Long userId, MarginTransferDto dto) {
        MarginAccount account = userMarginAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("Marzni racun za userId=" + userId + " ne postoji."));

        if (!account.isActive()) {
            throw new IllegalStateException("Racun je blokiran; isplate nisu moguce.");
        }

        BigDecimal newInitial = account.getInitialMargin().subtract(dto.getAmount());
        if (newInitial.compareTo(account.getMaintenanceMargin()) < 0) {
            throw new IllegalStateException(
                    "Isplata bi spustila initialMargin ispod maintenanceMargin (novo=" + newInitial
                            + ", min=" + account.getMaintenanceMargin() + ").");
        }

        account.setInitialMargin(newInitial);
        marginAccountService.recalcActive(account);
        marginAccountRepository.save(account);

        String toAccount = resolveCheckingAccountNumber(userId, dto.getFromAccountNumber());
        creditChecking(toAccount, dto.getAmount(), userId);

        log.info("[withdrawFromMargin] userId={} amount={} to={} initialMargin={}",
                userId, dto.getAmount(), toAccount, account.getInitialMargin());
        recordTransaction(account, dto.getAmount().negate(), MarginTransactionType.WITHDRAW_FROM_MARGIN,
                "Transfer from margin to checking " + toAccount);
    }

    /**
     * Bira broj tekuceg racuna ka kojem se vrsi debit/credit. Ako je {@code requested}
     * postavljen, koristi se direktno; inace banking-core poziva account-service da
     * dobije listu racuna klijenta i bira prvi RSD CHECKING (PERSONAL/BUSINESS).
     *
     * @throws IllegalStateException ako klijent nema RSD tekuci racun ili
     *         AccountServiceClient nije dostupan (npr. local profil).
     */
    private String resolveCheckingAccountNumber(Long userId, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "AccountServiceClient nije konfigurisan (verovatno local profil). "
                            + "U tom slucaju eksplicitno proslediti fromAccountNumber.");
        }
        return client.findClientAccounts(userId).stream()
                .filter(a -> "RSD".equals(a.currency()))
                .filter(a -> a.accountType() != null && a.accountType().endsWith("CHECKING")
                        || "PERSONAL".equals(a.accountType()) || "BUSINESS".equals(a.accountType())
                        || a.accountType() == null)  // tolerancija za starije payload-ove
                .map(AccountServiceClient.AccountDetails::accountNumber)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Klijent userId=" + userId + " nema RSD tekuci racun za margin transfer."));
    }

    private void debitChecking(String accountNumber, BigDecimal amount, Long clientId) {
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("AccountServiceClient nije dostupan — debit nije moguc.");
        }
        client.debit(accountNumber, amount, clientId);
    }

    private void creditChecking(String accountNumber, BigDecimal amount, Long clientId) {
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("AccountServiceClient nije dostupan — credit nije moguc.");
        }
        client.credit(accountNumber, amount, clientId);
    }

    /**
     * Spec C3.7: vraca sve transakcije sa zadatim brojem marznog racuna —
     * ukljucuje i placanja (negativan amount) i primanja (pozitivan amount).
     */
    @Transactional(readOnly = true)
    public List<MarginTransactionHistoryItemDto> getAllForAccountNumber(String accountNumber) {
        return marginTransactionRepository.findByAccountNumberOrderByOccurredAtDesc(accountNumber)
                .stream()
                .map(tx -> MarginTransactionHistoryItemDto.builder()
                        .id(tx.getId())
                        .accountNumber(tx.getAccountNumber())
                        .amount(tx.getAmount())
                        .transactionType(tx.getTransactionType().name())
                        .occurredAt(tx.getOccurredAt())
                        .description(tx.getDescription())
                        .build())
                .toList();
    }

    private void recordTransaction(MarginAccount account, BigDecimal amount, MarginTransactionType type, String description) {
        MarginTransaction tx = new MarginTransaction();
        tx.setAccountNumber(account.getAccountNumber());
        tx.setAmount(amount);
        tx.setTransactionType(type);
        tx.setLoanValueAfter(account.getLoanValue());
        tx.setInitialMarginAfter(account.getInitialMargin());
        tx.setDescription(description);
        marginTransactionRepository.save(tx);
    }

    // ------------------------------------------------------------------
    // Pomocni
    // ------------------------------------------------------------------

    private void validateMutuallyExclusive(StockMarginTransactionDto dto) {
        if ((dto.getUserId() == null) == (dto.getCompanyId() == null)) {
            throw new IllegalArgumentException(
                    "Tacno jedan od userId/companyId mora biti postavljen (spec: jedan ce sigurno biti null).");
        }
    }

    private MarginAccount lockAccountForOwner(Long userId, Long companyId) {
        if (userId != null) {
            return userMarginAccountRepository.findByUserId(userId)
                    .map(a -> marginAccountRepository.findByAccountNumberForUpdate(a.getAccountNumber()).orElseThrow())
                    .orElseThrow(() -> new IllegalArgumentException("Marzni racun za userId=" + userId + " ne postoji."));
        }
        return companyMarginAccountRepository.findByCompanyId(companyId)
                .map(a -> marginAccountRepository.findByAccountNumberForUpdate(a.getAccountNumber()).orElseThrow())
                .orElseThrow(() -> new IllegalArgumentException("Marzni racun za companyId=" + companyId + " ne postoji."));
    }
}
