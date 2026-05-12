package com.banka1.bankingcore.transaction.service.margin;

import com.banka1.bankingcore.account.client.AccountServiceClient;
import com.banka1.bankingcore.account.domain.margin.MarginAccount;
import com.banka1.bankingcore.account.domain.margin.UserMarginAccount;
import com.banka1.bankingcore.account.repository.margin.CompanyMarginAccountRepository;
import com.banka1.bankingcore.account.repository.margin.MarginAccountRepository;
import com.banka1.bankingcore.account.repository.margin.UserMarginAccountRepository;
import com.banka1.bankingcore.account.service.margin.MarginAccountService;
import com.banka1.bankingcore.transaction.dto.margin.MarginTransferDto;
import com.banka1.bankingcore.transaction.dto.margin.StockMarginTransactionDto;
import com.banka1.bankingcore.transaction.repository.margin.MarginTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarginTransactionServiceTest {

    @Mock private MarginAccountRepository marginAccountRepository;
    @Mock private UserMarginAccountRepository userMarginAccountRepository;
    @Mock private CompanyMarginAccountRepository companyMarginAccountRepository;
    @Mock private MarginAccountService marginAccountService;
    @Mock private MarginTransactionRepository marginTransactionRepository;
    @Mock private BankToExchangeTransferService bankToExchangeTransferService;
    @Mock private ObjectProvider<AccountServiceClient> accountServiceClientProvider;
    @Mock private AccountServiceClient accountServiceClient;

    @InjectMocks private MarginTransactionService service;

    private UserMarginAccount fixtureAccount;

    @BeforeEach
    void setUp() {
        fixtureAccount = new UserMarginAccount();
        fixtureAccount.setUserId(100L);
        fixtureAccount.setAccountNumber("1234567890123456");
        fixtureAccount.setInitialMargin(new BigDecimal("50000"));
        fixtureAccount.setLoanValue(BigDecimal.ZERO);
        fixtureAccount.setMaintenanceMargin(new BigDecimal("20000"));
        fixtureAccount.setBankParticipation(new BigDecimal("0.30"));
        fixtureAccount.setActive(true);
    }

    @Test
    void buyOnMargin_skidaClientPart_iDodajeBankPartNaLoanValue() {
        StockMarginTransactionDto dto = new StockMarginTransactionDto(100L, null, new BigDecimal("10000"));
        when(userMarginAccountRepository.findByUserId(100L)).thenReturn(Optional.of(fixtureAccount));
        when(marginAccountRepository.findByAccountNumberForUpdate("1234567890123456"))
                .thenReturn(Optional.of((MarginAccount) fixtureAccount));
        when(bankToExchangeTransferService.sendToExchange(any(), anyString())).thenReturn(999L);

        service.buyOnMargin(dto);

        // bankPart = 10000 * 0.30 = 3000 → loanValue
        // clientPart = 7000 → initialMargin = 50000 - 7000 = 43000
        assertThat(fixtureAccount.getInitialMargin()).isEqualByComparingTo("43000");
        assertThat(fixtureAccount.getLoanValue()).isEqualByComparingTo("3000.00");
        verify(bankToExchangeTransferService).sendToExchange(any(), anyString());
    }

    @Test
    void buyOnMargin_throws_kadJeRacunBlokiran() {
        fixtureAccount.setActive(false);
        StockMarginTransactionDto dto = new StockMarginTransactionDto(100L, null, new BigDecimal("10000"));
        when(userMarginAccountRepository.findByUserId(100L)).thenReturn(Optional.of(fixtureAccount));
        when(marginAccountRepository.findByAccountNumberForUpdate(anyString()))
                .thenReturn(Optional.of((MarginAccount) fixtureAccount));

        assertThatThrownBy(() -> service.buyOnMargin(dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blokiran");
        verify(bankToExchangeTransferService, never()).sendToExchange(any(), anyString());
    }

    @Test
    void buyOnMargin_throws_kadInitialMarginNeprovesokriva() {
        fixtureAccount.setInitialMargin(new BigDecimal("1000"));
        StockMarginTransactionDto dto = new StockMarginTransactionDto(100L, null, new BigDecimal("10000"));
        when(userMarginAccountRepository.findByUserId(100L)).thenReturn(Optional.of(fixtureAccount));
        when(marginAccountRepository.findByAccountNumberForUpdate(anyString()))
                .thenReturn(Optional.of((MarginAccount) fixtureAccount));

        assertThatThrownBy(() -> service.buyOnMargin(dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nedovoljno");
    }

    @Test
    void sellOnMargin_smanjuje_loanValue_iVisak_dodaje_na_initial() {
        fixtureAccount.setLoanValue(new BigDecimal("2000"));
        StockMarginTransactionDto dto = new StockMarginTransactionDto(100L, null, new BigDecimal("10000"));
        when(userMarginAccountRepository.findByUserId(100L)).thenReturn(Optional.of(fixtureAccount));
        when(marginAccountRepository.findByAccountNumberForUpdate(anyString()))
                .thenReturn(Optional.of((MarginAccount) fixtureAccount));
        when(bankToExchangeTransferService.receiveFromExchange(any(), anyString())).thenReturn(888L);

        service.sellOnMargin(dto);

        // bankPart = 10000 * 0.30 = 3000; loanReduction = min(2000, 3000) = 2000
        // clientCredit = 7000 + (3000 - 2000) = 8000
        assertThat(fixtureAccount.getLoanValue()).isEqualByComparingTo("0");
        assertThat(fixtureAccount.getInitialMargin()).isEqualByComparingTo("58000");
        verify(bankToExchangeTransferService).receiveFromExchange(any(), anyString());
    }

    @Test
    void validacija_throws_kadSuObaIliNijedanIDPoslati() {
        StockMarginTransactionDto bad = new StockMarginTransactionDto(100L, 50L, new BigDecimal("100"));
        assertThatThrownBy(() -> service.buyOnMargin(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tacno jedan");

        StockMarginTransactionDto neither = new StockMarginTransactionDto(null, null, new BigDecimal("100"));
        assertThatThrownBy(() -> service.buyOnMargin(neither))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addToMargin_dodaje_na_initial_i_debituje_klijentski_tekuci() {
        fixtureAccount.setInitialMargin(new BigDecimal("15000"));
        fixtureAccount.setActive(false);  // blokiran jer ispod maintenance
        when(userMarginAccountRepository.findByUserId(100L)).thenReturn(Optional.of(fixtureAccount));
        when(accountServiceClientProvider.getIfAvailable()).thenReturn(accountServiceClient);

        MarginTransferDto dto = new MarginTransferDto(new BigDecimal("10000"), "1234567812345670");
        service.addToMarginForUser(100L, dto);

        // Margin update.
        assertThat(fixtureAccount.getInitialMargin()).isEqualByComparingTo("25000");
        verify(marginAccountService).recalcActive(fixtureAccount);
        // Pravi REST debit ka account-service.
        verify(accountServiceClient).debit(eq("1234567812345670"), eq(new BigDecimal("10000")), eq(100L));
    }

    @Test
    void addToMargin_throws_kadAccountClientNijeDostupan() {
        when(userMarginAccountRepository.findByUserId(100L)).thenReturn(Optional.of(fixtureAccount));
        when(accountServiceClientProvider.getIfAvailable()).thenReturn(null);

        // Bez fromAccountNumber, bez klijenta — nema kako da resolve-uje.
        assertThatThrownBy(() ->
                service.addToMarginForUser(100L, new MarginTransferDto(new BigDecimal("100"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AccountServiceClient");
    }

    @Test
    void withdrawFromMargin_throws_kadIspodMaintenance() {
        fixtureAccount.setInitialMargin(new BigDecimal("25000"));
        when(userMarginAccountRepository.findByUserId(100L)).thenReturn(Optional.of(fixtureAccount));

        // Withdraw 10000 -> bi spustilo na 15000 < maintenance (20000)
        assertThatThrownBy(() ->
                service.withdrawFromMarginForUser(100L, new MarginTransferDto(new BigDecimal("10000"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maintenanceMargin");
    }

    @Test
    void withdrawFromMargin_throws_kadJeBlokiran() {
        fixtureAccount.setActive(false);
        when(userMarginAccountRepository.findByUserId(100L)).thenReturn(Optional.of(fixtureAccount));

        assertThatThrownBy(() ->
                service.withdrawFromMarginForUser(100L, new MarginTransferDto(new BigDecimal("100"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blokiran");
    }

    @Test
    void withdrawFromMargin_skida_sa_initial_i_kreditUje_klijentski_tekuci() {
        fixtureAccount.setInitialMargin(new BigDecimal("50000"));
        when(userMarginAccountRepository.findByUserId(100L)).thenReturn(Optional.of(fixtureAccount));
        when(accountServiceClientProvider.getIfAvailable()).thenReturn(accountServiceClient);

        MarginTransferDto dto = new MarginTransferDto(new BigDecimal("5000"), "1234567812345670");
        service.withdrawFromMarginForUser(100L, dto);

        assertThat(fixtureAccount.getInitialMargin()).isEqualByComparingTo("45000");
        verify(accountServiceClient).credit(eq("1234567812345670"), eq(new BigDecimal("5000")), eq(100L));
    }
}
