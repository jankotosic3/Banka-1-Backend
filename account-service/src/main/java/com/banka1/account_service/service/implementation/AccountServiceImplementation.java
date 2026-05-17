package com.banka1.account_service.service.implementation;

import com.banka1.account_service.domain.Account;
import com.banka1.account_service.domain.Currency;
import com.banka1.account_service.domain.SystemAccountIds;
import com.banka1.account_service.domain.enums.CurrencyCode;
import com.banka1.account_service.domain.enums.Status;
import com.banka1.account_service.dto.request.BankPaymentDto;
import com.banka1.account_service.dto.request.CreditDebitAccountDto;
import com.banka1.account_service.dto.request.CreditDebitBankDto;
import com.banka1.account_service.dto.request.OneSidedTransactionDto;
import com.banka1.account_service.dto.request.PaymentDto;
import com.banka1.account_service.dto.response.InfoResponseDto;
import com.banka1.account_service.dto.response.InternalAccountDetailsDto;
import com.banka1.account_service.dto.response.UpdatedBalanceResponseDto;
import com.banka1.account_service.repository.AccountRepository;
import com.banka1.account_service.repository.CurrencyRepository;
import com.banka1.account_service.service.AccountService;
import com.banka1.account_service.service.TransactionalService;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Implementacija servisa za izvrsavanje internih transakcija i transfera.
 * <p>
 * Validira račune, njihove statusu i saldone, zatim delegira atomičnu
 * operaciju transfera na {@link TransactionalService}. Koristi retry logiku
 * za optimističke lock greške.
 */
@RequiredArgsConstructor
@Service
@Slf4j
public class AccountServiceImplementation implements AccountService {
    /** Servis za atomične debitne/kreditne operacije. */
    private final TransactionalService transactionalService;
    /** Repozitorijum za pristup računima iz baze. */
    private final AccountRepository accountRepository;

    private final CurrencyRepository currencyRepository;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Validira da račun postoji, ima ACTIVE status i nije istekao.
     *
     * @param accountNumber broj računa koji se validira
     * @return validiran Account objekat
     * @throws IllegalArgumentException ako račun ne postoji, nije aktivan ili je istekao
     */
    private Account validate(String accountNumber)
    {
        Account account = accountRepository.findByBrojRacuna(accountNumber).orElse(null);
        if(account==null)
            throw new IllegalArgumentException("Ne postoji racun:"+accountNumber);
        if(account.getStatus()== Status.INACTIVE)
            throw new IllegalArgumentException("Racun je neaktivan:"+accountNumber);
        if(account.getDatumIsteka()!=null&&account.getDatumIsteka().isBefore(LocalDate.now()))
            throw new IllegalArgumentException("Racun je istekao:"+accountNumber);
        return account;
    }
    /**
     * Pronalazi i validira banka-račun u tražnoj valuti.
     * <p>
     * Banka-računi se identifikuju po {@link SystemAccountIds#BANK} (vlasnikID=-1L)
     * i valuti računa. PR_29: zamenjen magic broj {@code -1L} konstantom.
     *
     * @param to račun čija valuta se koristi za pronalaženje odgovarajućeg banka-računa
     * @return validiran Account banka-račun
     * @throws IllegalStateException ako banka-račun ne postoji, nije aktivan ili je istekao
     */
    private Account validateBank(Account to)
    {
        Account account=accountRepository.findByVlasnikAndCurrency(SystemAccountIds.BANK,to.getCurrency()).orElse(null);
        if(account==null)
            throw new IllegalStateException("Greska u sistemu fali banka");
        if(account.getStatus()== Status.INACTIVE)
            throw new IllegalStateException("Racun banke je neaktivan");
        if(account.getDatumIsteka()!=null&&account.getDatumIsteka().isBefore(LocalDate.now()))
            throw new IllegalStateException("Racun banke je istekao");
        return account;
    }

    private Account validateBank(CurrencyCode currencyCode)
    {
        Currency currency=currencyRepository.findByOznaka(currencyCode).orElse(null);
        if(currency==null)
            throw new IllegalArgumentException("Ne postoji ovaj currency "+currencyCode);
        Account account=accountRepository.findByVlasnikAndCurrency(SystemAccountIds.BANK,currency).orElse(null);
        if(account==null)
            throw new IllegalStateException("Greska u sistemu fali banka");
        if(account.getStatus()== Status.INACTIVE)
            throw new IllegalStateException("Racun banke je neaktivan");
        if(account.getDatumIsteka()!=null&&account.getDatumIsteka().isBefore(LocalDate.now()))
            throw new IllegalStateException("Racun banke je istekao");
        return account;
    }

    /**
     * Izvršava transfer sa retry logikom za optimističke lock greške.
     * <p>
     * Prvo validira vlasnika računa, zatim pokušava transfer sa do 3 pokušaja
     * u slučaju konkurentnog pristupa istom računu.
     *
     * @param paymentDto podaci o transakciji
     * @param from izvorni račun
     * @param to odredišni račun
     * @param bankSender banka-račun posiljaoca
     * @param bankTarget banka-račun primaoca
     * @return azurirani saldoi nakon transfera
     * @throws IllegalArgumentException ako korisnik nije vlasnik računa
     * @throws ObjectOptimisticLockingFailureException ako se greška ponovi 3 puta
     */
    private UpdatedBalanceResponseDto execute(PaymentDto paymentDto, Account from, Account to, Account bankSender, Account bankTarget) {
        if(!from.getVlasnik().equals(paymentDto.getClientId()))
            throw new IllegalArgumentException("Nisi vlasnik racuna");
        for(int i = 0; true; i++) {
            try {
                return transactionalService.transfer(from,to,bankSender,bankTarget,paymentDto);
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException optimisticLockException) {
                if(i>=2)
                    throw optimisticLockException;
            }
        }
    }

    @Override
    public void creditBank(CreditDebitBankDto creditDebitBankDto) {
        CurrencyCode currencyCode;
        try
        {
            currencyCode=CurrencyCode.valueOf(creditDebitBankDto.getCurrencyCode().toUpperCase());
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("Ne postoji ovaj currencyCode: "+ creditDebitBankDto.getCurrencyCode());
        }
        Account account=validateBank(currencyCode);
        for(int i = 0; true; i++) {
            try {
                transactionalService.creditTransactional(account, creditDebitBankDto.getAmount());
                return;
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException optimisticLockException) {
                if(i>=2)
                    throw optimisticLockException;
            }
        }
    }

    @Override
    public void creditAccount(CreditDebitAccountDto creditDebitAccountDto) {
        Account account=validate(creditDebitAccountDto.getAccountNumber());
        if(!account.getVlasnik().equals(-1L) && !account.getVlasnik().equals(creditDebitAccountDto.getClientId()))
            throw new IllegalArgumentException("Nisi vlasnik racuna");
        for(int i = 0; true; i++) {
            try {
                transactionalService.creditTransactional(account, creditDebitAccountDto.getAmount());
                return;
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException optimisticLockException) {
                if(i>=2)
                    throw optimisticLockException;
            }
        }
    }

    @Override
    public void debitBank(CreditDebitBankDto creditDebitBankDto) {
        CurrencyCode currencyCode;
        try
        {
            currencyCode=CurrencyCode.valueOf(creditDebitBankDto.getCurrencyCode().toUpperCase());
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("Ne postoji ovaj currencyCode: "+ creditDebitBankDto.getCurrencyCode());
        }
        Account account=validateBank(currencyCode);
        for(int i = 0; true; i++) {
            try {
                transactionalService.debitTransactional(account, creditDebitBankDto.getAmount());
                return;
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException optimisticLockException) {
                if(i>=2)
                    throw optimisticLockException;
            }
        }
    }

    @Override
    public void debitAccount(CreditDebitAccountDto creditDebitAccountDto) {
        Account account=validate(creditDebitAccountDto.getAccountNumber());
        if(!account.getVlasnik().equals(-1L) && !account.getVlasnik().equals(creditDebitAccountDto.getClientId()))
            throw new IllegalArgumentException("Nisi vlasnik racuna");
        for(int i = 0; true; i++) {
            try {
                transactionalService.debitTransactional(account, creditDebitAccountDto.getAmount());
                return;
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException optimisticLockException) {
                if(i>=2)
                    throw optimisticLockException;
            }
        }
    }

    @Override
    public UpdatedBalanceResponseDto transaction(PaymentDto paymentDto) {
        Account from=validate(paymentDto.getFromAccountNumber());
        Account to=validate(paymentDto.getToAccountNumber());
        Account bankSender=validateBank(from);
        Account bankTarget=validateBank(to);
        if(paymentDto.getClientId()==null)
            throw new IllegalArgumentException("Unesi id clienta");
        if(from.getVlasnik().equals(to.getVlasnik()))
            throw new IllegalArgumentException("Tranzakcija se ne moze odvijati za racune istog vlasnike");
        UpdatedBalanceResponseDto result = execute(paymentDto, from, to, bankSender, bankTarget);
        recordPayment(from, to, paymentDto.getFromAmount(), paymentDto.getToAmount(), paymentDto.getCommission(), "Payment");
        return result;
    }

    @Override
    public void transactionFromBank(BankPaymentDto paymentDto) {
        if(paymentDto.getFromAccountNumber()==null && paymentDto.getToAccountNumber()==null)
            throw new IllegalArgumentException("Los unos");
        Account sender;
        Account recipient;
        if(paymentDto.getFromAccountNumber()==null) {
            recipient = validate(paymentDto.getToAccountNumber());
            sender = validateBank(recipient);
        }
        else
        {
            sender = validate(paymentDto.getFromAccountNumber());
            recipient = validateBank(sender);
        }
        for(int i = 0; true; i++) {
            try {
                transactionalService.transfer(sender,recipient,paymentDto);
                return;
            } catch (ObjectOptimisticLockingFailureException | OptimisticLockException optimisticLockException) {
                if(i>=2)
                    throw optimisticLockException;
            }
        }
    }


    @Override
    public UpdatedBalanceResponseDto transfer(PaymentDto paymentDto) {
        Account from=validate(paymentDto.getFromAccountNumber());
        Account to=validate(paymentDto.getToAccountNumber());
        Account bankSender=validateBank(from);
        Account bankTarget=validateBank(to);
        if(paymentDto.getClientId()==null)
            throw new IllegalArgumentException("Unesi id clienta");
        if(!from.getVlasnik().equals(to.getVlasnik()))
            throw new IllegalArgumentException("Transfer se moze odvijati samo za racune istog vlasnika");
        UpdatedBalanceResponseDto result = execute(paymentDto, from, to, bankSender, bankTarget);
        recordPayment(from, to, paymentDto.getFromAmount(), paymentDto.getToAmount(), paymentDto.getCommission(), "Transfer");
        return result;
    }

    @Override
    public InternalAccountDetailsDto getAccountDetails(String accountNumber) {
        Account account = accountRepository.findByBrojRacuna(accountNumber).orElse(null);
        if (account == null)
            throw new NoSuchElementException("Ne postoji racun:" + accountNumber);
        return InternalAccountDetailsDto.from(account);
    }

    @Override
    public InternalAccountDetailsDto getAccountDetails(Long accountId) {
        Account account = accountRepository.findById(accountId).orElse(null);
        if (account == null)
            throw new NoSuchElementException("Ne postoji racun id:" + accountId);
        return InternalAccountDetailsDto.from(account);
    }

    @Override
    public InternalAccountDetailsDto getBankAccountDetails(CurrencyCode currencyCode) {
        Account account = accountRepository.findBankAccountByCurrencyCode(currencyCode).orElse(null);
        if (account == null)
            throw new NoSuchElementException("Ne postoji interni bankovni racun za valutu:" + currencyCode);
        return InternalAccountDetailsDto.from(account);
    }

    @Override
    @Transactional
    public UpdatedBalanceResponseDto exchangeBuy(OneSidedTransactionDto request) {
        Account account = resolveAccountForOneSided(request);
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Iznos mora biti pozitivan");
        }
        transactionalService.withdrawOneSided(account, request.getAmount());
        recordPayment(account, validateBank(account), request.getAmount(), request.getAmount(), BigDecimal.ZERO, "Stock purchase");
        return new UpdatedBalanceResponseDto(account.getRaspolozivoStanje(), null);
    }

    @Override
    @Transactional
    public UpdatedBalanceResponseDto exchangeSell(OneSidedTransactionDto request) {
        Account account = resolveAccountForOneSided(request);
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new IllegalArgumentException("Iznos mora biti pozitivan");
        }
        transactionalService.depositOneSided(account, request.getAmount());
        recordPayment(validateBank(account), account, request.getAmount(), request.getAmount(), BigDecimal.ZERO, "Stock sale");
        return new UpdatedBalanceResponseDto(account.getRaspolozivoStanje(), null);
    }

    // Writes a payment_table record so the movement appears in the user-facing transaction list.
    // Fail-safe: a write failure is logged but never propagated — the balance update has already
    // committed in its own transaction and must not be rolled back or misclassified as a debit failure.
    private void recordPayment(Account from, Account to, BigDecimal fromAmount, BigDecimal toAmount,
                               BigDecimal commission, String purpose) {
        try {
            String fromCurrency = currencyCode(from);
            String toCurrency = currencyCode(to);
            String firstName = to.getImeVlasnikaRacuna() != null ? to.getImeVlasnikaRacuna() : "";
            String lastName = to.getPrezimeVlasnikaRacuna() != null ? to.getPrezimeVlasnikaRacuna() : "";
            String recipientName = (firstName + " " + lastName).trim();
            if (recipientName.isEmpty()) {
                recipientName = to.getBrojRacuna();
            }
            String orderNumber = UUID.randomUUID().toString();
            jdbcTemplate.update(
                    "INSERT INTO payment_table "
                            + "(from_account_number, to_account_number, initial_amount, final_amount, commission, "
                            + " sender_client_id, recipient_client_id, recipient_name, "
                            + " payment_code, reference_number, payment_purpose, status, "
                            + " from_currency, to_currency, order_number, created_at, updated_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, '289', ?, ?, 'COMPLETED', ?, ?, ?, NOW(), NOW(), 0)",
                    from.getBrojRacuna(), to.getBrojRacuna(), fromAmount, toAmount,
                    commission != null ? commission : BigDecimal.ZERO,
                    from.getVlasnik(), to.getVlasnik(), recipientName,
                    orderNumber, purpose,
                    fromCurrency, toCurrency,
                    orderNumber);
        } catch (Exception ex) {
            log.error("Failed to write payment_table record for movement from {} to {} amount={}: {}",
                    from.getBrojRacuna(), to.getBrojRacuna(), fromAmount, ex.getMessage(), ex);
        }
    }

    private String currencyCode(Account account) {
        return account.getCurrency() != null && account.getCurrency().getOznaka() != null
                ? account.getCurrency().getOznaka().name()
                : "RSD";
    }

    /**
     * Razresava racun iz {@link OneSidedTransactionDto}: prvo probaj po
     * {@code accountNumber}, fallback na {@code accountId}. Validacija je ista
     * kao u {@link #validate(String)}.
     */
    private Account resolveAccountForOneSided(OneSidedTransactionDto request) {
        if (request == null) {
            throw new IllegalArgumentException("Request ne sme biti null");
        }
        if (request.getAccountNumber() != null && !request.getAccountNumber().isBlank()) {
            return validate(request.getAccountNumber());
        }
        if (request.getAccountId() != null) {
            Account account = accountRepository.findById(request.getAccountId()).orElseThrow(
                    () -> new IllegalArgumentException("Ne postoji racun za id:" + request.getAccountId()));
            if (account.getStatus() == Status.INACTIVE)
                throw new IllegalArgumentException("Racun je neaktivan:" + account.getBrojRacuna());
            if (account.getDatumIsteka() != null && account.getDatumIsteka().isBefore(LocalDate.now()))
                throw new IllegalArgumentException("Racun je istekao:" + account.getBrojRacuna());
            return account;
        }
        throw new IllegalArgumentException("OneSidedTransactionDto mora imati accountNumber ili accountId");
    }

    @Override
    public InternalAccountDetailsDto getStateAccountDetails(CurrencyCode currencyCode) {
        Account account = accountRepository.findStateAccountByCurrencyCode(currencyCode).orElse(null);
        if (account == null)
            throw new NoSuchElementException("Ne postoji drzavni racun za valutu:" + currencyCode);
        return InternalAccountDetailsDto.from(account);
    }

    @Override
    public InfoResponseDto info(Jwt jwt, String fromAccountNumber, String toAccountNumber) {
        Account fromAccount=accountRepository.findByBrojRacuna(fromAccountNumber).orElse(null);
        if(fromAccount==null)
            throw new IllegalArgumentException("Ne postoji from racun");
        if(fromAccount.getStatus()==Status.INACTIVE)
            throw new IllegalArgumentException("FromAccount nije aktivan");
        Account toAccount=accountRepository.findByBrojRacuna(toAccountNumber).orElse(null);
        if(toAccount==null)
            throw new IllegalArgumentException("Ne postoji to racun");
        if(toAccount.getStatus()==Status.INACTIVE)
            throw new IllegalArgumentException("ToAccount nije aktivan");
        return new InfoResponseDto(fromAccount.getCurrency().getOznaka(), toAccount.getCurrency().getOznaka(), fromAccount.getVlasnik(), toAccount.getVlasnik(),fromAccount.getEmail(),fromAccount.getUsername());

    }

    @Override
    public InternalAccountDetailsDto createSystemAccount(com.banka1.account_service.dto.request.CreateSystemAccountDto dto) {
        // Idempotentnost: ako sistem racun sa zadatim brojem vec postoji, vrati ga.
        Account existing = accountRepository.findByBrojRacuna(dto.getAccountNumber()).orElse(null);
        if (existing != null) {
            return InternalAccountDetailsDto.from(existing);
        }

        Currency currency = currencyRepository.findByOznaka(dto.getCurrencyCode())
                .orElseThrow(() -> new IllegalArgumentException("Valuta " + dto.getCurrencyCode() + " ne postoji."));

        java.math.BigDecimal initialBalance = dto.getInitialBalance() != null
                ? dto.getInitialBalance()
                : java.math.BigDecimal.ZERO;

        Account account;
        if (dto.getAccountConcrete().getAccountOwnershipType() == com.banka1.account_service.domain.enums.AccountOwnershipType.PERSONAL) {
            account = new com.banka1.account_service.domain.CheckingAccount(dto.getAccountConcrete());
        } else {
            // BUSINESS — koristi se za fond/sistemske racune (FONDACIJA, DOO, AD).
            account = new com.banka1.account_service.domain.CheckingAccount(dto.getAccountConcrete());
        }

        account.setBrojRacuna(dto.getAccountNumber());
        account.setNazivRacuna(dto.getDisplayName());
        account.setVlasnik(dto.getOwnerId());
        account.setZaposlen(-1L);
        account.setImeVlasnikaRacuna("SYSTEM");
        account.setPrezimeVlasnikaRacuna(dto.getDisplayName());
        account.setUsername("system-" + dto.getOwnerId());
        account.setEmail("system+" + dto.getOwnerId() + "@banka1.local");
        account.setDatumIVremeKreiranja(java.time.LocalDateTime.now());
        account.setCurrency(currency);
        account.setStanje(initialBalance);
        account.setRaspolozivoStanje(initialBalance);
        account.setDatumIsteka(LocalDate.now().plusYears(50));  // efektivno bez isteka

        Account saved = accountRepository.save(account);
        return InternalAccountDetailsDto.from(saved);
    }
}
