package com.banka1.saga_orchestrator.service;

import com.banka1.saga_orchestrator.client.BankingCoreClient;
import com.banka1.saga_orchestrator.client.MarketServiceClient;
import com.banka1.saga_orchestrator.domain.SagaInstance;
import com.banka1.saga_orchestrator.domain.SagaState;
import com.banka1.saga_orchestrator.domain.SagaType;
import com.banka1.saga_orchestrator.repository.SagaInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Premium transfer saga (PR_11 C11.5 real implementacija; zameni stub iz PR_04 C4.11).
 *
 * <p>Kada OTC ponuda dobije {@code ACCEPTED} status, trading-service.OtcService
 * publishuje {@code saga.OTC_PREMIUM_TRANSFER.START.command}. Ova saga prebacuje
 * premium iznos sa kupcevog na prodavcev racun preko banking-core internal-transfer
 * REST poziva.
 *
 * <p>Single-step — bez kompenzacije osim manualne (premium se vec naplatio jer je
 * kupoprodaja na medjusobnoj saglasnosti pre ovog koraka). Ako transfer fail-uje,
 * status saga-e postaje FAILED i alert ide na admin oncall — premium ostaje "duzan",
 * ali OptionContract je vec kreiran (idempotent).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtcPremiumTransferSaga {

    private final SagaInstanceRepository sagaRepo;
    private final BankingCoreClient banking;
    private final MarketServiceClient market;
    private final RabbitTemplate rabbitTemplate;

    @Transactional
    public void run(Map<String, Object> event) {
        String correlationId = String.valueOf(event.get("contractId"));
        String transferCorrelationId = "otc-premium-" + correlationId;

        SagaInstance existing = sagaRepo.findBySagaTypeAndCorrelationId(SagaType.OTC_PREMIUM_TRANSFER, correlationId).orElse(null);
        if (existing != null && existing.isFinalState()) {
            log.info("OTC_PREMIUM_TRANSFER saga {} vec u {} — preskocenam", correlationId, existing.getState());
            return;
        }

        SagaInstance saga = (existing != null) ? existing : initialize(correlationId, event);
        saga.setState(SagaState.IN_PROGRESS);
        sagaRepo.save(saga);

        Long buyerId = ((Number) event.get("buyerId")).longValue();
        Long sellerId = ((Number) event.get("sellerId")).longValue();
        BigDecimal premiumUsd = new BigDecimal(String.valueOf(event.get("premium")));
        // Default account is always RSD; premium is negotiated in USD — convert before transfer.
        String premiumCurrency = event.get("premiumCurrency") != null
                ? String.valueOf(event.get("premiumCurrency")) : "USD";

        try {
            saga.setCurrentStep(1);
            String buyerAccount = banking.resolveDefaultAccountNumber(buyerId);
            String sellerAccount = banking.resolveDefaultAccountNumber(sellerId);

            BigDecimal transferAmount = "RSD".equalsIgnoreCase(premiumCurrency)
                    ? premiumUsd
                    : market.convertCurrencyNoCommission(premiumUsd, premiumCurrency, "RSD");
            log.info("OTC premium: {} {} -> {} RSD (contractId={})", premiumUsd, premiumCurrency, transferAmount, correlationId);

            BankingCoreClient.TransferResult result = banking.internalTransfer(
                    buyerAccount, sellerAccount, transferAmount, transferCorrelationId);

            Map<String, Object> compensationLog = new LinkedHashMap<>();
            compensationLog.put("step1_transferId", result.transferId());
            saga.setCompensationLog(compensationLog);
            saga.setState(SagaState.COMPLETED);
            sagaRepo.save(saga);
            log.info("OTC_PREMIUM_TRANSFER saga {} OK (transfer {})", correlationId, result.transferId());
            rabbitTemplate.convertAndSend("saga.events", "otc.premium.transfer.completed",
                    Map.of("contractId", Long.parseLong(correlationId)));
        } catch (Exception ex) {
            log.error("OTC_PREMIUM_TRANSFER saga {} FAILED: {}", correlationId, ex.toString());
            Map<String, Object> failureLog = new LinkedHashMap<>();
            failureLog.put("failureReason", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName());
            failureLog.put("alertRequired", true);
            saga.setCompensationLog(failureLog);
            saga.setState(SagaState.FAILED);
            sagaRepo.save(saga);
        }
    }

    private SagaInstance initialize(String correlationId, Map<String, Object> event) {
        SagaInstance saga = new SagaInstance();
        saga.setSagaType(SagaType.OTC_PREMIUM_TRANSFER);
        saga.setCorrelationId(correlationId);
        saga.setTotalSteps(SagaType.OTC_PREMIUM_TRANSFER.getTotalSteps());
        saga.setCurrentStep(0);
        saga.setState(SagaState.STARTED);
        saga.setPayload(event);
        return saga;
    }
}
