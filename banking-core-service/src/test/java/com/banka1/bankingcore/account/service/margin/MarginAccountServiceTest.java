package com.banka1.bankingcore.account.service.margin;

import com.banka1.bankingcore.account.domain.margin.UserMarginAccount;
import com.banka1.bankingcore.account.dto.margin.CreateUserMarginAccountDto;
import com.banka1.bankingcore.account.dto.margin.MarginAccountResponseDto;
import com.banka1.bankingcore.account.repository.margin.CompanyMarginAccountRepository;
import com.banka1.bankingcore.account.repository.margin.MarginAccountRepository;
import com.banka1.bankingcore.account.repository.margin.UserMarginAccountRepository;
import com.banka1.bankingcore.account.service.AccountNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarginAccountServiceTest {

    @Mock private MarginAccountRepository marginRepo;
    @Mock private UserMarginAccountRepository userRepo;
    @Mock private CompanyMarginAccountRepository companyRepo;
    @Mock private AccountNumberGenerator accountNumberGenerator;

    @InjectMocks private MarginAccountService service;

    @BeforeEach
    void setUp() {
        when(accountNumberGenerator.generate()).thenReturn("1234567890123456");
    }

    @Test
    void createForUser_kreiraRacunSaActiveTrue_kadInitialPrekoMaintenance() {
        CreateUserMarginAccountDto dto = new CreateUserMarginAccountDto(
                1L, 100L,
                new BigDecimal("50000"),  // initial
                new BigDecimal("20000"),  // maintenance
                new BigDecimal("0.3"));   // bank participation
        when(userRepo.existsByUserId(100L)).thenReturn(false);
        when(userRepo.save(any())).thenAnswer(inv -> {
            UserMarginAccount a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });

        MarginAccountResponseDto resp = service.createForUser(dto);

        assertThat(resp.isActive()).isTrue();
        assertThat(resp.getInitialMargin()).isEqualByComparingTo("50000");
        assertThat(resp.getLoanValue()).isEqualByComparingTo("0");
        assertThat(resp.getAccountNumber()).isEqualTo("1234567890123456");
        assertThat(resp.getUserId()).isEqualTo(100L);
    }

    @Test
    void createForUser_throwsKadVecPostoji() {
        CreateUserMarginAccountDto dto = new CreateUserMarginAccountDto(
                1L, 100L, BigDecimal.TEN, BigDecimal.ONE, new BigDecimal("0.3"));
        when(userRepo.existsByUserId(100L)).thenReturn(true);

        assertThatThrownBy(() -> service.createForUser(dto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vec ima marzni racun");
    }

    @Test
    void recalcActive_postaviActiveFalse_kadInitialPadneIspodMaintenance() {
        UserMarginAccount acc = new UserMarginAccount();
        acc.setActive(true);
        acc.setInitialMargin(new BigDecimal("15000"));   // ispod maintenance
        acc.setMaintenanceMargin(new BigDecimal("20000"));

        service.recalcActive(acc);

        assertThat(acc.isActive()).isFalse();
    }

    @Test
    void recalcActive_postaviActiveTrue_kadInitialPredjeMaintenance() {
        UserMarginAccount acc = new UserMarginAccount();
        acc.setActive(false);
        acc.setInitialMargin(new BigDecimal("25000"));   // iznad
        acc.setMaintenanceMargin(new BigDecimal("20000"));

        service.recalcActive(acc);

        assertThat(acc.isActive()).isTrue();
    }
}
