package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.domain.ClientFundPosition;
import com.banka1.tradingservice.funds.domain.ClientFundTransaction;
import com.banka1.tradingservice.funds.domain.ClientFundTransactionStatus;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.dto.CreateFundRequest;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import com.banka1.tradingservice.funds.dto.InvestmentRequest;
import com.banka1.tradingservice.funds.dto.RedemptionRequest;
import com.banka1.tradingservice.funds.client.AccountServiceClient;
import com.banka1.tradingservice.funds.repository.ClientFundPositionRepository;
import com.banka1.tradingservice.funds.repository.ClientFundTransactionRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvestmentFundServiceTest {

    @Mock private InvestmentFundRepository fundRepository;
    @Mock private ClientFundPositionRepository positionRepository;
    @Mock private ClientFundTransactionRepository transactionRepository;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private FundAccountNumberGenerator accountNumberGenerator;
    @Mock private FundHoldingService fundHoldingService;
    @Mock private ObjectProvider<AccountServiceClient> accountServiceClientProvider;

    @InjectMocks private InvestmentFundService service;

    private InvestmentFund fixture;

    @BeforeEach
    void setUp() {
        fixture = new InvestmentFund();
        fixture.setId(1L);
        fixture.setNaziv("Alpha Growth");
        fixture.setMinimumContribution(new BigDecimal("1000"));
        fixture.setLikvidnaSredstva(new BigDecimal("100000"));
        fixture.setManagerId(50L);
        fixture.setAccountNumber("1234567812345674");

        // holdings value = 0 unless overridden per test
        lenient().when(fundHoldingService.calculateHoldingsValue(anyLong())).thenReturn(BigDecimal.ZERO);
        // no account-service available in unit tests — fund creation skips REST call
        lenient().when(accountServiceClientProvider.getIfAvailable()).thenReturn(null);
    }

    @Test
    void createFund_kreira_sa_zero_likvidnih_i_generickim_account() {
        when(accountNumberGenerator.generate()).thenReturn("9999999999999999");
        when(fundRepository.save(any())).thenAnswer(inv -> {
            InvestmentFund f = inv.getArgument(0);
            f.setId(2L);
            return f;
        });
        when(positionRepository.findByFundId(2L)).thenReturn(List.of());

        CreateFundRequest req = new CreateFundRequest("Beta Fund", "Tech", new BigDecimal("500"));

        InvestmentFundDto dto = service.createFund(req, 60L);

        assertThat(dto.getNaziv()).isEqualTo("Beta Fund");
        assertThat(dto.getLikvidnaSredstva()).isEqualByComparingTo("0");
        assertThat(dto.getAccountNumber()).isEqualTo("9999999999999999");
        assertThat(dto.getManagerId()).isEqualTo(60L);
    }

    @Test
    void invest_throws_kadJeAmountManjiOdMinimum() {
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixture));
        InvestmentRequest req = new InvestmentRequest(new BigDecimal("500"), "ACC-1");

        assertThatThrownBy(() -> service.invest(1L, 100L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumContribution");
    }

    @Test
    void invest_kreira_pending_transaction() {
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixture));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            ClientFundTransaction t = inv.getArgument(0);
            t.setId(99L);
            return t;
        });

        ClientFundTransaction tx = service.invest(1L, 100L,
                new InvestmentRequest(new BigDecimal("5000"), "ACC-1"));

        assertThat(tx.getStatus()).isEqualTo(ClientFundTransactionStatus.PENDING);
        assertThat(tx.isInflow()).isTrue();
        assertThat(tx.getAmount()).isEqualByComparingTo("5000");
    }

    @Test
    void redeem_throws_kadKlijentNemaPoziciju() {
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixture));
        when(positionRepository.findByClientIdAndFundIdForUpdate(100L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.redeem(1L, 100L, new RedemptionRequest(new BigDecimal("500"), "ACC-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nema poziciju");
    }

    @Test
    void redeem_throws_kadJeAmountVeciOdTrenutnePozicijeVrednosti() {
        // Small fund — liquidity 2000, sole investor with 1000 invested
        // -> currentPositionValue = (1000/1000) * 2000 = 2000
        // -> request 5000 > 2000 -> exception
        InvestmentFund smallFund = new InvestmentFund();
        smallFund.setId(1L);
        smallFund.setLikvidnaSredstva(new BigDecimal("2000"));
        smallFund.setMinimumContribution(BigDecimal.ONE);
        smallFund.setAccountNumber("ACC");

        ClientFundPosition pos = new ClientFundPosition();
        pos.setTotalInvested(new BigDecimal("1000"));

        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(smallFund));
        when(positionRepository.findByClientIdAndFundIdForUpdate(100L, 1L)).thenReturn(Optional.of(pos));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(pos));

        assertThatThrownBy(() ->
                service.redeem(1L, 100L, new RedemptionRequest(new BigDecimal("5000"), "ACC-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("veca");
    }

    @Test
    void redeem_kreira_pending_transaction_outflow() {
        // fixture liquidity = 100000, sole investor with 10000 invested
        // -> currentPositionValue = 100000 -> 3000 < 100000 -> OK
        ClientFundPosition pos = new ClientFundPosition();
        pos.setTotalInvested(new BigDecimal("10000"));

        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixture));
        when(positionRepository.findByClientIdAndFundIdForUpdate(100L, 1L)).thenReturn(Optional.of(pos));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(pos));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            ClientFundTransaction t = inv.getArgument(0);
            t.setId(99L);
            return t;
        });

        ClientFundTransaction tx = service.redeem(1L, 100L,
                new RedemptionRequest(new BigDecimal("3000"), "ACC-1"));

        assertThat(tx.isInflow()).isFalse();
        assertThat(tx.getStatus()).isEqualTo(ClientFundTransactionStatus.PENDING);
    }

    @Test
    void reassignManager_prebacuje_sve_fondove() {
        InvestmentFund f1 = new InvestmentFund();
        f1.setManagerId(50L);
        InvestmentFund f2 = new InvestmentFund();
        f2.setManagerId(50L);
        when(fundRepository.findByManagerIdAndDeletedFalse(50L)).thenReturn(List.of(f1, f2));

        service.reassignManager(50L, 75L);

        assertThat(f1.getManagerId()).isEqualTo(75L);
        assertThat(f2.getManagerId()).isEqualTo(75L);
    }
}
