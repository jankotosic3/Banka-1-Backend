package com.banka1.bankingcore.interbank;

import com.banka1.account_service.domain.Account;
import com.banka1.account_service.domain.Currency;
import com.banka1.account_service.domain.enums.CurrencyCode;
import com.banka1.account_service.domain.enums.Status;
import com.banka1.account_service.repository.AccountRepository;
import com.banka1.bankingcore.interbank.model.InterbankReservation;
import com.banka1.bankingcore.interbank.repository.InterbankReservationRepository;
import com.banka1.bankingcore.interbank.service.InterbankReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito unit testovi za {@link InterbankReservationService} (PR_32 Phase 11).
 *
 * <p>Cilj: pokriti happy path-ove (reserve, commit, release) + edge case-ove
 * (insufficient balance, idempotent commit/release).
 */
@ExtendWith(MockitoExtension.class)
class InterbankReservationServiceTest {

    @Mock private InterbankReservationRepository reservationRepository;
    @Mock private AccountRepository accountRepository;

    @InjectMocks private InterbankReservationService service;

    private Account account;

    @BeforeEach
    void setUp() {
        Currency currency = new Currency(
                "Srpski dinar", CurrencyCode.RSD, "din",
                Set.of("Srbija"), "Valuta Srbije", Status.ACTIVE);

        account = new TestAccount();
        account.setBrojRacuna("111000110000000312");
        account.setCurrency(currency);
        account.setStanje(new BigDecimal("10000.0000"));
        account.setRaspolozivoStanje(new BigDecimal("10000.0000"));
        account.setVlasnik(-1L);
    }

    @Test
    void reserveMonas_happyPath_decreasesRaspolozivoStanjeAndPersistsReservation() {
        when(accountRepository.findByBrojRacuna("111000110000000312"))
                .thenReturn(Optional.of(account));

        UUID resId = service.reserveMonas(
                "111000110000000312", "RSD", new BigDecimal("250.0000"),
                42, "TX-LOCAL-1");

        assertThat(resId).isNotNull();
        assertThat(account.getRaspolozivoStanje())
                .isEqualByComparingTo(new BigDecimal("9750.0000"));
        assertThat(account.getStanje())
                .isEqualByComparingTo(new BigDecimal("10000.0000")); // unchanged

        ArgumentCaptor<InterbankReservation> captor =
                ArgumentCaptor.forClass(InterbankReservation.class);
        verify(reservationRepository).save(captor.capture());
        InterbankReservation saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("HELD");
        assertThat(saved.getAccountNumber()).isEqualTo("111000110000000312");
        assertThat(saved.getCurrency()).isEqualTo("RSD");
        assertThat(saved.getTransactionIdRouting()).isEqualTo(42);
        assertThat(saved.getTransactionIdLocal()).isEqualTo("TX-LOCAL-1");
        assertThat(saved.getReservationId()).isEqualTo(resId);
    }

    @Test
    void reserveMonas_insufficientBalance_throws() {
        when(accountRepository.findByBrojRacuna("111000110000000312"))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.reserveMonas(
                "111000110000000312", "RSD", new BigDecimal("20000.0000"),
                42, "TX-LOCAL-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Insufficient");

        // Available balance unchanged, no reservation persisted.
        assertThat(account.getRaspolozivoStanje())
                .isEqualByComparingTo(new BigDecimal("10000.0000"));
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void reserveMonas_currencyMismatch_throws() {
        when(accountRepository.findByBrojRacuna("111000110000000312"))
                .thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.reserveMonas(
                "111000110000000312", "EUR", new BigDecimal("100.0000"),
                42, "TX-LOCAL-3"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");

        verify(reservationRepository, never()).save(any());
    }

    @Test
    void commitReservation_happyPath_decreasesStanjeAndMarksCommitted() {
        UUID resId = UUID.randomUUID();
        InterbankReservation reservation = InterbankReservation.builder()
                .reservationId(resId)
                .transactionIdRouting(42)
                .transactionIdLocal("TX-LOCAL-1")
                .accountNumber("111000110000000312")
                .currency("RSD")
                .amount(new BigDecimal("250.0000"))
                .status("HELD")
                .build();
        // Simulate post-reserve state: raspolozivo already reduced.
        account.setRaspolozivoStanje(new BigDecimal("9750.0000"));

        when(reservationRepository.findByReservationId(resId))
                .thenReturn(Optional.of(reservation));
        when(accountRepository.findByBrojRacuna("111000110000000312"))
                .thenReturn(Optional.of(account));

        service.commitReservation(resId);

        // Stanje decreased; raspolozivo stays as it was post-reserve.
        assertThat(account.getStanje())
                .isEqualByComparingTo(new BigDecimal("9750.0000"));
        assertThat(account.getRaspolozivoStanje())
                .isEqualByComparingTo(new BigDecimal("9750.0000"));
        assertThat(reservation.getStatus()).isEqualTo("COMMITTED");
        assertThat(reservation.getFinalizedAt()).isNotNull();
        verify(reservationRepository).save(reservation);
    }

    @Test
    void commitReservation_idempotent_alreadyCommitted_isNoOp() {
        UUID resId = UUID.randomUUID();
        InterbankReservation reservation = InterbankReservation.builder()
                .reservationId(resId)
                .accountNumber("111000110000000312")
                .amount(new BigDecimal("250.0000"))
                .status("COMMITTED")
                .build();
        when(reservationRepository.findByReservationId(resId))
                .thenReturn(Optional.of(reservation));

        service.commitReservation(resId);

        // No save, no balance touch.
        verify(reservationRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void releaseReservation_happyPath_restoresRaspolozivoStanje() {
        UUID resId = UUID.randomUUID();
        InterbankReservation reservation = InterbankReservation.builder()
                .reservationId(resId)
                .accountNumber("111000110000000312")
                .currency("RSD")
                .amount(new BigDecimal("250.0000"))
                .status("HELD")
                .build();
        // Post-reserve state.
        account.setRaspolozivoStanje(new BigDecimal("9750.0000"));

        when(reservationRepository.findByReservationId(resId))
                .thenReturn(Optional.of(reservation));
        when(accountRepository.findByBrojRacuna("111000110000000312"))
                .thenReturn(Optional.of(account));

        service.releaseReservation(resId);

        assertThat(account.getRaspolozivoStanje())
                .isEqualByComparingTo(new BigDecimal("10000.0000"));
        assertThat(account.getStanje())
                .isEqualByComparingTo(new BigDecimal("10000.0000")); // untouched
        assertThat(reservation.getStatus()).isEqualTo("RELEASED");
        assertThat(reservation.getFinalizedAt()).isNotNull();
    }

    @Test
    void releaseReservation_alreadyCommitted_throws() {
        UUID resId = UUID.randomUUID();
        InterbankReservation reservation = InterbankReservation.builder()
                .reservationId(resId)
                .accountNumber("111000110000000312")
                .amount(new BigDecimal("250.0000"))
                .status("COMMITTED")
                .build();
        when(reservationRepository.findByReservationId(resId))
                .thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.releaseReservation(resId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already COMMITTED");

        verify(reservationRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void releaseReservation_idempotent_alreadyReleased_isNoOp() {
        UUID resId = UUID.randomUUID();
        InterbankReservation reservation = InterbankReservation.builder()
                .reservationId(resId)
                .accountNumber("111000110000000312")
                .amount(new BigDecimal("250.0000"))
                .status("RELEASED")
                .build();
        when(reservationRepository.findByReservationId(resId))
                .thenReturn(Optional.of(reservation));

        service.releaseReservation(resId);

        verify(reservationRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    /**
     * Minimal concrete {@link Account} subclass — Account je abstract sa
     * SINGLE_TABLE inheritance i ne moze direktno da se instancira.
     */
    private static class TestAccount extends Account {
        // empty — koristi @Setter sa Account-a + nase {@code setUp()} field-ove.
    }
}
