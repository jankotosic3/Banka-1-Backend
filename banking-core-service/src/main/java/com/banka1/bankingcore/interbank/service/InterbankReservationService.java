package com.banka1.bankingcore.interbank.service;

import com.banka1.account_service.domain.Account;
import com.banka1.account_service.repository.AccountRepository;
import com.banka1.bankingcore.interbank.model.InterbankReservation;
import com.banka1.bankingcore.interbank.repository.InterbankReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Banking-core servis koji ekspozira interbank 2PC primitive (PR_32 Phase 11).
 *
 * <p>Pozivaju ga {@code /internal/interbank/*} endpoint-i koje interbank-service
 * gadja kroz {@code BankingCoreInternalClient}. Servis radi direktno nad
 * legacy Account entity-jem (banking-core ucitava {@code account-service} kao
 * library — PR_19 C19.X), bez REST hop-a.
 *
 * <p>Razlika od {@code FundReservationService}:
 * <ul>
 *   <li>{@code FundReservationService} (006-fund-reservations) — SAGA orchestrator
 *       koraci, kljuc = correlation_id + owner_id, RSD-only.</li>
 *   <li>{@code InterbankReservationService} (009-interbank-reservations) —
 *       inter-banka 2PC, kljuc = (transaction_id_routing, transaction_id_local)
 *       iz Tim 2 protokola, multi-currency, podrzava bilo koji racun po
 *       broju racuna.</li>
 * </ul>
 *
 * <p>Pattern za balanse:
 * <ul>
 *   <li>{@code reserveMonas} — smanjuje {@code raspolozivoStanje} (available)
 *       ali ne dira {@code stanje} (booked). Klijent ne moze trositi
 *       rezervisani iznos, ali ekstrakt jos uvek pokazuje punu sumu.</li>
 *   <li>{@code commitReservation} — skida i sa {@code stanje}. Rezervacija
 *       se evidentira kao definitivno odlazece.</li>
 *   <li>{@code releaseReservation} — vraca {@code raspolozivoStanje} (since
 *       {@code stanje} nije nikad skinut, ovde se ne dira).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterbankReservationService {

    public static final String STATUS_HELD = "HELD";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_RELEASED = "RELEASED";

    private final InterbankReservationRepository reservationRepository;
    private final AccountRepository accountRepository;

    /**
     * Rezervise iznos na racunu — smanjuje {@code raspolozivoStanje}.
     *
     * @return UUID nove rezervacije; interbank-service ga pamti za kasniji
     *         commit/release poziv.
     * @throws IllegalArgumentException ako racun ne postoji, valuta se ne
     *         poklapa ili nema dovoljno raspolozivih sredstava.
     */
    @Transactional
    public UUID reserveMonas(String accountNumber,
                              String currency,
                              BigDecimal amount,
                              int transactionIdRouting,
                              String transactionIdLocal) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        Account account = accountRepository.findByBrojRacuna(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account not found: " + accountNumber));

        // Currency match check (Account.currency is Currency entity sa
        // CurrencyCode enum-om u polju oznaka).
        String accountCurrencyCode = account.getCurrency() != null
                && account.getCurrency().getOznaka() != null
                ? account.getCurrency().getOznaka().name()
                : null;
        if (accountCurrencyCode == null || !accountCurrencyCode.equals(currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: account=" + accountCurrencyCode
                            + " requested=" + currency);
        }

        BigDecimal available = account.getRaspolozivoStanje();
        if (available == null || available.compareTo(amount) < 0) {
            throw new IllegalArgumentException(
                    "Insufficient available balance: have="
                            + available + " need=" + amount);
        }

        account.setRaspolozivoStanje(available.subtract(amount));
        accountRepository.save(account);

        UUID reservationId = UUID.randomUUID();
        InterbankReservation reservation = InterbankReservation.builder()
                .reservationId(reservationId)
                .transactionIdRouting(transactionIdRouting)
                .transactionIdLocal(transactionIdLocal)
                .accountNumber(accountNumber)
                .currency(currency)
                .amount(amount)
                .status(STATUS_HELD)
                .build();
        reservationRepository.save(reservation);

        log.info("Interbank reserveMonas: account={} amount={} currency={} txRouting={} txLocal={} resId={}",
                accountNumber, amount, currency, transactionIdRouting,
                transactionIdLocal, reservationId);
        return reservationId;
    }

    /**
     * Commit-uje rezervaciju — skida iznos sa {@code stanje} (full balance).
     * Idempotentno: ako je vec COMMITTED, no-op.
     *
     * @throws IllegalArgumentException ako rezervacija ne postoji.
     * @throws IllegalStateException ako je rezervacija u RELEASED state-u
     *         (ne moze se commit-ovati nakon release-a).
     */
    @Transactional
    public void commitReservation(UUID reservationId) {
        InterbankReservation reservation = reservationRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Reservation not found: " + reservationId));

        if (STATUS_COMMITTED.equals(reservation.getStatus())) {
            log.info("Interbank commit: reservation {} already COMMITTED — idempotent no-op",
                    reservationId);
            return;
        }
        if (!STATUS_HELD.equals(reservation.getStatus())) {
            throw new IllegalStateException(
                    "Cannot commit reservation " + reservationId
                            + " in state " + reservation.getStatus());
        }

        Account account = accountRepository.findByBrojRacuna(reservation.getAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account vanished: " + reservation.getAccountNumber()));
        BigDecimal stanje = account.getStanje();
        if (stanje == null) {
            stanje = BigDecimal.ZERO;
        }
        account.setStanje(stanje.subtract(reservation.getAmount()));
        accountRepository.save(account);

        reservation.setStatus(STATUS_COMMITTED);
        reservation.setFinalizedAt(Instant.now());
        reservationRepository.save(reservation);

        log.info("Interbank commit: reservation={} account={} amount={}",
                reservationId, reservation.getAccountNumber(), reservation.getAmount());
    }

    /**
     * Oslobadja rezervaciju — vraca {@code raspolozivoStanje} na originalnu
     * vrednost. Idempotentno: ako je vec RELEASED, no-op.
     *
     * @throws IllegalArgumentException ako rezervacija ne postoji.
     * @throws IllegalStateException ako je rezervacija vec COMMITTED.
     */
    @Transactional
    public void releaseReservation(UUID reservationId) {
        InterbankReservation reservation = reservationRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Reservation not found: " + reservationId));

        if (STATUS_RELEASED.equals(reservation.getStatus())) {
            log.info("Interbank release: reservation {} already RELEASED — idempotent no-op",
                    reservationId);
            return;
        }
        if (STATUS_COMMITTED.equals(reservation.getStatus())) {
            throw new IllegalStateException(
                    "Cannot release reservation " + reservationId + " — already COMMITTED");
        }

        Account account = accountRepository.findByBrojRacuna(reservation.getAccountNumber())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Account vanished: " + reservation.getAccountNumber()));
        BigDecimal available = account.getRaspolozivoStanje();
        if (available == null) {
            available = BigDecimal.ZERO;
        }
        account.setRaspolozivoStanje(available.add(reservation.getAmount()));
        accountRepository.save(account);

        reservation.setStatus(STATUS_RELEASED);
        reservation.setFinalizedAt(Instant.now());
        reservationRepository.save(reservation);

        log.info("Interbank release: reservation={} account={} amount={}",
                reservationId, reservation.getAccountNumber(), reservation.getAmount());
    }
}
