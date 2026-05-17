package com.banka1.saga_orchestrator.service;

import com.banka1.saga_orchestrator.client.BankingCoreClient;
import com.banka1.saga_orchestrator.client.MarketServiceClient;
import com.banka1.saga_orchestrator.client.TradingServiceClient;
import com.banka1.saga_orchestrator.domain.SagaInstance;
import com.banka1.saga_orchestrator.domain.SagaState;
import com.banka1.saga_orchestrator.domain.SagaType;
import com.banka1.saga_orchestrator.repository.SagaInstanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OtcExerciseSagaTest {

    @Mock private SagaInstanceRepository sagaRepo;
    @Mock private BankingCoreClient banking;
    @Mock private MarketServiceClient market;
    @Mock private TradingServiceClient trading;
    @Mock private ObjectMapper objectMapper;
    @Mock private RabbitTemplate rabbitTemplate;

    @InjectMocks private OtcExerciseSaga saga;

    private Map<String, Object> exerciseEvent() {
        Map<String, Object> e = new HashMap<>();
        e.put("contractId", 1L);
        e.put("buyerId", 100L);
        e.put("sellerId", 200L);
        e.put("stockTicker", "AAPL");
        e.put("amount", 10);
        e.put("pricePerStock", "150.00");
        return e;
    }

    @Test
    void run_completed_kada_svi_step_uspesni() {
        when(sagaRepo.findBySagaTypeAndCorrelationId(SagaType.OTC_EXERCISE, "1"))
                .thenReturn(Optional.empty());
        when(sagaRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(market.convertCurrencyNoCommission(any(), eq("USD"), eq("RSD")))
                .thenAnswer(inv -> inv.getArgument(0));
        when(banking.reserveFunds(eq(100L), any(), eq("otc-exercise-1")))
                .thenReturn(new BankingCoreClient.ReservationResult("res-1", "OK"));
        when(trading.reserveStocks(eq(200L), eq("AAPL"), eq(10), eq("otc-exercise-1")))
                .thenReturn(new TradingServiceClient.StockReservationResult("stock-1", "OK"));
        when(banking.resolveDefaultAccountNumber(100L)).thenReturn("BUYER-ACC");
        when(banking.resolveDefaultAccountNumber(200L)).thenReturn("SELLER-ACC");
        when(banking.internalTransfer(eq("BUYER-ACC"), eq("SELLER-ACC"), any(), eq("otc-exercise-1")))
                .thenReturn(new BankingCoreClient.TransferResult("tx-1", "OK"));
        when(trading.transferOwnership(eq("stock-1"), eq(100L), eq("otc-exercise-1")))
                .thenReturn(new TradingServiceClient.OwnershipTransferResult("own-1", "OK"));

        saga.run(exerciseEvent());

        verify(sagaRepo, atLeast(1)).save(argThat(s ->
                s.getState() == SagaState.COMPLETED && s.getCurrentStep() == 5));
    }

    @Test
    void run_compensates_kada_step_3_fail() {
        when(sagaRepo.findBySagaTypeAndCorrelationId(SagaType.OTC_EXERCISE, "1"))
                .thenReturn(Optional.empty());
        when(sagaRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(market.convertCurrencyNoCommission(any(), eq("USD"), eq("RSD")))
                .thenAnswer(inv -> inv.getArgument(0));
        when(banking.reserveFunds(eq(100L), any(), eq("otc-exercise-1")))
                .thenReturn(new BankingCoreClient.ReservationResult("res-1", "OK"));
        when(trading.reserveStocks(eq(200L), eq("AAPL"), eq(10), eq("otc-exercise-1")))
                .thenReturn(new TradingServiceClient.StockReservationResult("stock-1", "OK"));
        when(banking.resolveDefaultAccountNumber(anyLong())).thenReturn("ACC");
        when(banking.internalTransfer(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Banking down"));

        saga.run(exerciseEvent());

        // Compensations: step2 + step1 (kompenzacija u obrnutom redosledu)
        verify(trading).releaseStocks("stock-1", "otc-exercise-1");
        verify(banking).releaseFunds("res-1", "otc-exercise-1");
        verify(sagaRepo, atLeast(1)).save(argThat(s -> s.getState() == SagaState.FAILED));
    }

    @Test
    void run_skip_kada_saga_vec_completed() {
        SagaInstance existing = new SagaInstance();
        existing.setState(SagaState.COMPLETED);
        when(sagaRepo.findBySagaTypeAndCorrelationId(SagaType.OTC_EXERCISE, "1"))
                .thenReturn(Optional.of(existing));

        saga.run(exerciseEvent());

        verify(banking, never()).reserveFunds(anyLong(), any(), any());
        verify(trading, never()).reserveStocks(anyLong(), any(), anyInt(), any());
    }
}
