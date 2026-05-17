package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.client.AccountServiceClient;
import com.banka1.tradingservice.funds.client.UserServiceClient;
import com.banka1.tradingservice.funds.domain.ClientFundPosition;
import com.banka1.tradingservice.funds.domain.ClientFundTransaction;
import com.banka1.tradingservice.funds.domain.ClientFundTransactionStatus;
import com.banka1.tradingservice.funds.domain.FundHolding;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.dto.ClientFundPositionDto;
import com.banka1.tradingservice.funds.dto.CreateFundRequest;
import com.banka1.tradingservice.funds.dto.FundHoldingDto;
import com.banka1.tradingservice.funds.dto.FundPerformancePointDto;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import com.banka1.tradingservice.funds.dto.InvestmentRequest;
import com.banka1.tradingservice.funds.dto.RedemptionRequest;
import com.banka1.tradingservice.funds.repository.ClientFundPositionRepository;
import com.banka1.tradingservice.funds.repository.ClientFundTransactionRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvestmentFundService {

    /** Rezervisani clientId koji označava banku kao investitora u fond. */
    public static final Long BANK_INVESTOR_ID = -1L;

    private final InvestmentFundRepository fundRepository;
    private final ClientFundPositionRepository positionRepository;
    private final ClientFundTransactionRepository transactionRepository;
    private final RabbitTemplate rabbitTemplate;
    private final FundAccountNumberGenerator accountNumberGenerator;
    private final FundHoldingService fundHoldingService;
    private final ObjectProvider<AccountServiceClient> accountServiceClientProvider;
    private final ObjectProvider<UserServiceClient> userServiceClientProvider;

    private static final String SAGA_EVENTS_EXCHANGE = "saga.events";

    // ----------------------------- create ----------------------------

    @Transactional
    public InvestmentFundDto createFund(CreateFundRequest req, Long managerId) {
        String accountNumber = accountNumberGenerator.generate();

        InvestmentFund fund = new InvestmentFund();
        fund.setNaziv(req.getNaziv());
        fund.setOpis(req.getOpis());
        fund.setMinimumContribution(req.getMinimumContribution());
        fund.setManagerId(managerId);
        fund.setLikvidnaSredstva(BigDecimal.ZERO);
        fund.setAccountNumber(accountNumber);

        InvestmentFund saved = fundRepository.save(fund);

        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client != null) {
            try {
                client.createSystemAccount(
                        accountNumber,
                        -1000L - saved.getId(),
                        "RSD",
                        "Investicioni fond: " + saved.getNaziv(),
                        BigDecimal.ZERO);
                log.info("Account fonda {} (id={}) kreiran", accountNumber, saved.getId());
            } catch (Exception ex) {
                log.error("Account fonda {} (id={}) NIJE kreiran: {}", accountNumber, saved.getId(), ex.toString());
                throw new IllegalStateException("Account fonda nije kreiran.", ex);
            }
        } else {
            throw new IllegalStateException("AccountServiceClient nije dostupan. Fond nije kreiran.");
        }

        log.info("Created InvestmentFund {} ('{}') by manager {}", saved.getId(), saved.getNaziv(), managerId);
        return toDto(saved);
    }

    // ----------------------------- read ------------------------------

    @Transactional(readOnly = true)
    public List<InvestmentFundDto> discovery() {
        return fundRepository.findByDeletedFalseOrderByNazivAsc()
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public InvestmentFundDto details(Long fundId) {
        return fundRepository.findById(fundId)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));
    }

    @Transactional(readOnly = true)
    public List<ClientFundPositionDto> myPositions(Long clientId) {
        return positionRepository.findByClientId(clientId)
                .stream().map(this::toPositionDto).toList();
    }

    @Transactional(readOnly = true)
    public List<InvestmentFundDto> supervisedBy(Long managerId) {
        return fundRepository.findByManagerIdAndDeletedFalse(managerId)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<FundHoldingDto> getEnrichedHoldings(Long fundId) {
        if (!fundRepository.existsById(fundId)) {
            throw new IllegalArgumentException("Fond " + fundId + " ne postoji.");
        }
        return fundHoldingService.enrichedHoldings(fundId);
    }

    @Transactional(readOnly = true)
    public List<ClientFundPositionDto> bankPositions() {
        return positionRepository.findByClientId(BANK_INVESTOR_ID)
                .stream().map(this::toPositionDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ClientFundPositionDto> fundPositions(Long fundId) {
        if (!fundRepository.existsById(fundId)) {
            throw new IllegalArgumentException("Fond " + fundId + " ne postoji.");
        }
        return positionRepository.findByFundId(fundId)
                .stream().map(this::toPositionDto).toList();
    }

    @Transactional(readOnly = true)
    public List<ClientFundTransaction> myTransactions(Long clientId) {
        return transactionRepository.findByClientIdOrderByOccurredAtDesc(clientId);
    }

    @Transactional(readOnly = true)
    public List<ClientFundTransaction> fundTransactions(Long fundId) {
        if (!fundRepository.existsById(fundId)) {
            throw new IllegalArgumentException("Fond " + fundId + " ne postoji.");
        }
        return transactionRepository.findByFundIdOrderByOccurredAtDesc(fundId);
    }

    @Transactional(readOnly = true)
    public List<FundPerformancePointDto> fundPerformance(Long fundId) {
        InvestmentFund fund = fundRepository.findById(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));
        BigDecimal totalValue = computeFundValue(fund);
        BigDecimal investedSum = positionRepository.findByFundId(fundId).stream()
                .map(ClientFundPosition::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profit = totalValue.subtract(investedSum);

        List<ClientFundTransaction> transactions = transactionRepository.findByFundIdOrderByOccurredAtDesc(fundId);
        if (transactions.isEmpty()) {
            return List.of(FundPerformancePointDto.builder()
                    .timestamp(fund.getDatumKreiranja().atStartOfDay())
                    .totalValue(totalValue)
                    .profit(profit)
                    .build());
        }
        return transactions.stream()
                .map(tx -> FundPerformancePointDto.builder()
                        .timestamp(tx.getOccurredAt())
                        .transactionId(tx.getId())
                        .amount(tx.getAmount())
                        .inflow(tx.isInflow())
                        .status(tx.getStatus())
                        .totalValue(totalValue)
                        .profit(profit)
                        .build())
                .toList();
    }

    @Transactional
    public void debitLiquidity(Long fundId, BigDecimal amount, String reason) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));
        BigDecimal current = fund.getLikvidnaSredstva() == null ? BigDecimal.ZERO : fund.getLikvidnaSredstva();
        fund.setLikvidnaSredstva(current.subtract(amount).setScale(2, RoundingMode.HALF_UP));
        fundRepository.save(fund);
        log.info("Fund liquidity debited: fundId={} amount={} newLiquidity={} reason={}",
                fundId, amount, fund.getLikvidnaSredstva(), reason);
    }

    // ----------------------------- client invest/redeem --------------

    @Transactional
    public ClientFundTransaction invest(Long fundId, Long clientId, InvestmentRequest req) {
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));
        ensureFundAccountExists(fund);

        if (req.getAmount().compareTo(fund.getMinimumContribution()) < 0) {
            throw new IllegalArgumentException(
                    "Iznos manji od minimumContribution (" + fund.getMinimumContribution() + ").");
        }

        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setClientId(clientId);
        tx.setFundId(fundId);
        tx.setAmount(req.getAmount());
        tx.setInflow(true);
        tx.setStatus(ClientFundTransactionStatus.PENDING);
        tx.setClientAccountNumber(req.getFromAccountNumber());
        ClientFundTransaction saved = transactionRepository.save(tx);

        registerAfterCommit(() -> rabbitTemplate.convertAndSend(
                SAGA_EVENTS_EXCHANGE, "fund.subscribe.requested",
                new FundSubscribeRequestedEvent(saved.getId(), clientId, fundId,
                        req.getAmount(), req.getFromAccountNumber(), fund.getAccountNumber())
        ));
        return saved;
    }

    @Transactional
    public ClientFundTransaction redeem(Long fundId, Long clientId, RedemptionRequest req) {
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));
        ensureFundAccountExists(fund);

        ClientFundPosition pos = positionRepository.findByClientIdAndFundIdForUpdate(clientId, fundId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Klijent " + clientId + " nema poziciju u fondu " + fundId));

        BigDecimal currentPositionValue = computeCurrentPositionValue(pos, fund);
        if (req.getAmount().compareTo(currentPositionValue) > 0) {
            throw new IllegalArgumentException(
                    "Trazena isplata (" + req.getAmount() + ") veca od trenutne vrednosti pozicije ("
                            + currentPositionValue + ").");
        }

        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setClientId(clientId);
        tx.setFundId(fundId);
        tx.setAmount(req.getAmount());
        tx.setInflow(false);
        tx.setStatus(ClientFundTransactionStatus.PENDING);
        tx.setClientAccountNumber(req.getToAccountNumber());
        ClientFundTransaction saved = transactionRepository.save(tx);

        boolean liquidEnough = fund.getLikvidnaSredstva().compareTo(req.getAmount()) >= 0;
        String routingKey = liquidEnough ? "fund.redeem.requested" : "fund.redeem.with-liquidation.requested";

        registerAfterCommit(() -> rabbitTemplate.convertAndSend(
                SAGA_EVENTS_EXCHANGE, routingKey,
                new FundRedeemRequestedEvent(saved.getId(), clientId, fundId,
                        req.getAmount(), req.getToAccountNumber(), fund.getAccountNumber(), liquidEnough)
        ));
        return saved;
    }

    // ----------------------------- bank invest/redeem ----------------

    @Transactional
    public ClientFundTransaction bankInvest(Long fundId, InvestmentRequest req) {
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));
        ensureFundAccountExists(fund);

        if (req.getAmount().compareTo(fund.getMinimumContribution()) < 0) {
            throw new IllegalArgumentException(
                    "Iznos manji od minimumContribution (" + fund.getMinimumContribution() + ").");
        }

        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setClientId(BANK_INVESTOR_ID);
        tx.setFundId(fundId);
        tx.setAmount(req.getAmount());
        tx.setInflow(true);
        tx.setStatus(ClientFundTransactionStatus.PENDING);
        tx.setClientAccountNumber(req.getFromAccountNumber());
        ClientFundTransaction saved = transactionRepository.save(tx);

        registerAfterCommit(() -> rabbitTemplate.convertAndSend(
                SAGA_EVENTS_EXCHANGE, "fund.subscribe.requested",
                new FundSubscribeRequestedEvent(saved.getId(), BANK_INVESTOR_ID, fundId,
                        req.getAmount(), req.getFromAccountNumber(), fund.getAccountNumber())
        ));
        return saved;
    }

    @Transactional
    public ClientFundTransaction bankRedeem(Long fundId, RedemptionRequest req) {
        InvestmentFund fund = fundRepository.findByIdForUpdate(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));
        ensureFundAccountExists(fund);

        ClientFundPosition pos = positionRepository.findByClientIdAndFundIdForUpdate(BANK_INVESTOR_ID, fundId)
                .orElseThrow(() -> new IllegalArgumentException("Banka nema poziciju u fondu " + fundId));

        BigDecimal currentPositionValue = computeCurrentPositionValue(pos, fund);
        if (req.getAmount().compareTo(currentPositionValue) > 0) {
            throw new IllegalArgumentException(
                    "Trazena isplata (" + req.getAmount() + ") veca od trenutne vrednosti bankine pozicije ("
                            + currentPositionValue + ").");
        }

        ClientFundTransaction tx = new ClientFundTransaction();
        tx.setClientId(BANK_INVESTOR_ID);
        tx.setFundId(fundId);
        tx.setAmount(req.getAmount());
        tx.setInflow(false);
        tx.setStatus(ClientFundTransactionStatus.PENDING);
        tx.setClientAccountNumber(req.getToAccountNumber());
        ClientFundTransaction saved = transactionRepository.save(tx);

        boolean liquidEnough = fund.getLikvidnaSredstva().compareTo(req.getAmount()) >= 0;
        String routingKey = liquidEnough ? "fund.redeem.requested" : "fund.redeem.with-liquidation.requested";

        registerAfterCommit(() -> rabbitTemplate.convertAndSend(
                SAGA_EVENTS_EXCHANGE, routingKey,
                new FundRedeemRequestedEvent(saved.getId(), BANK_INVESTOR_ID, fundId,
                        req.getAmount(), req.getToAccountNumber(), fund.getAccountNumber(), liquidEnough)
        ));
        return saved;
    }

    // ----------------------------- saga callbacks --------------------

    /**
     * Poziva se od strane {@code FundSubscribeResultListener} kada FUND_SUBSCRIBE saga
     * uspešno izvrši transfer. Ažurira transakciju, poziciju i likvidnost fonda.
     */
    @Transactional
    public void completeInvest(Long txId, BigDecimal amount, Long clientId, Long fundId) {
        ClientFundTransaction tx = transactionRepository.findById(txId).orElse(null);
        if (tx == null) {
            log.warn("completeInvest: txId={} not found — skip", txId);
            return;
        }
        if (tx.getStatus() != ClientFundTransactionStatus.PENDING) {
            log.info("completeInvest: txId={} already {} — skip", txId, tx.getStatus());
            return;
        }
        tx.setStatus(ClientFundTransactionStatus.COMPLETED);
        transactionRepository.save(tx);

        ClientFundPosition pos = positionRepository.findByClientIdAndFundId(clientId, fundId)
                .orElseGet(() -> {
                    ClientFundPosition p = new ClientFundPosition();
                    p.setClientId(clientId);
                    p.setFundId(fundId);
                    p.setTotalInvested(BigDecimal.ZERO);
                    return p;
                });
        pos.setTotalInvested(pos.getTotalInvested().add(amount));
        pos.setLastModifiedAt(LocalDateTime.now());
        positionRepository.save(pos);

        fundRepository.findByIdForUpdate(fundId).ifPresent(fund -> {
            fund.setLikvidnaSredstva(fund.getLikvidnaSredstva().add(amount));
            fundRepository.save(fund);
        });

        log.info("completeInvest: txId={} clientId={} fundId={} amount={}", txId, clientId, fundId, amount);
    }

    /**
     * Poziva se od strane result listenera kada saga ne uspe. Obeležava transakciju kao FAILED.
     */
    @Transactional
    public void failTransaction(Long txId, String reason) {
        transactionRepository.findById(txId).ifPresentOrElse(tx -> {
            if (tx.getStatus() == ClientFundTransactionStatus.PENDING) {
                tx.setStatus(ClientFundTransactionStatus.FAILED);
                tx.setFailureReason(reason);
                transactionRepository.save(tx);
                log.info("failTransaction: txId={} reason={}", txId, reason);
            } else {
                log.info("failTransaction: txId={} already {} — skip", txId, tx.getStatus());
            }
        }, () -> log.warn("failTransaction: txId={} not found", txId));
    }

    /**
     * Poziva se od strane {@code FundRedeemResultListener} i
     * {@code FundRedeemWithLiquidationResultListener} kada redeem saga uspešno završi.
     *
     * <p>Za liquidation sagu: FundLiquidationService je već povećao likvidnaSredstva
     * u step 1. Ovde smanjujemo za iznos koji je banking-core prebacio na klijenta (step 2).
     */
    @Transactional
    public void completeRedeem(Long txId, BigDecimal amount, Long clientId, Long fundId) {
        ClientFundTransaction tx = transactionRepository.findById(txId).orElse(null);
        if (tx == null) {
            log.warn("completeRedeem: txId={} not found — skip", txId);
            return;
        }
        if (tx.getStatus() != ClientFundTransactionStatus.PENDING) {
            log.info("completeRedeem: txId={} already {} — skip", txId, tx.getStatus());
            return;
        }
        tx.setStatus(ClientFundTransactionStatus.COMPLETED);
        transactionRepository.save(tx);

        positionRepository.findByClientIdAndFundId(clientId, fundId).ifPresent(pos -> {
            BigDecimal newInvested = pos.getTotalInvested().subtract(amount);
            pos.setTotalInvested(newInvested.max(BigDecimal.ZERO));
            pos.setLastModifiedAt(LocalDateTime.now());
            positionRepository.save(pos);
        });

        fundRepository.findByIdForUpdate(fundId).ifPresent(fund -> {
            BigDecimal newLiquidity = fund.getLikvidnaSredstva().subtract(amount);
            fund.setLikvidnaSredstva(newLiquidity.max(BigDecimal.ZERO));
            fundRepository.save(fund);
        });

        log.info("completeRedeem: txId={} clientId={} fundId={} amount={}", txId, clientId, fundId, amount);
    }

    // ----------------------------- admin -----------------------------

    @Transactional
    public void reassignManager(Long oldManagerId, Long newManagerId) {
        List<InvestmentFund> funds = fundRepository.findByManagerIdAndDeletedFalse(oldManagerId);
        for (InvestmentFund f : funds) {
            f.setManagerId(newManagerId);
        }
        log.info("Reassigned {} fund(s) from manager {} to {}", funds.size(), oldManagerId, newManagerId);
    }

    // ----------------------------- internal --------------------------

    private BigDecimal computeFundValue(InvestmentFund f) {
        BigDecimal holdingsValue = fundHoldingService.calculateHoldingsValue(f.getId());
        return f.getLikvidnaSredstva().add(holdingsValue);
    }

    private void ensureFundAccountExists(InvestmentFund fund) {
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            return;
        }
        try {
            client.createSystemAccount(
                    fund.getAccountNumber(),
                    -1000L - fund.getId(),
                    "RSD",
                    "Investicioni fond: " + fund.getNaziv(),
                    fund.getLikvidnaSredstva());
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Racun fonda " + fund.getAccountNumber() + " nije dostupan: " + ex.getMessage(), ex);
        }
    }

    private BigDecimal computeCurrentPositionValue(ClientFundPosition pos, InvestmentFund fund) {
        BigDecimal fundValue = computeFundValue(fund);
        BigDecimal totalFundInvested = positionRepository.findByFundId(fund.getId()).stream()
                .map(ClientFundPosition::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalFundInvested.signum() <= 0 || fundValue.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        // Use max(totalFundInvested, fundValue) as the denominator. When the fund holds
        // pre-existing assets not backed by any position record (e.g. previous investors
        // redeemed but holdings remain, or seed data), totalFundInvested < fundValue.
        // Dividing by totalFundInvested alone would assign 100% of those orphaned assets
        // to the remaining investors, inflating their position by 100x or more.
        BigDecimal denominator = totalFundInvested.max(fundValue);
        BigDecimal pct = pos.getTotalInvested().divide(denominator, 10, RoundingMode.HALF_UP);
        return pct.multiply(fundValue).setScale(2, RoundingMode.HALF_UP);
    }

    private InvestmentFundDto toDto(InvestmentFund f) {
        BigDecimal holdingsValue = fundHoldingService.calculateHoldingsValue(f.getId());
        BigDecimal totalValue = f.getLikvidnaSredstva().add(holdingsValue);
        BigDecimal investedSum = positionRepository.findByFundId(f.getId()).stream()
                .map(ClientFundPosition::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal profit = totalValue.subtract(investedSum);

        String managerIme = null;
        String managerPrezime = null;
        Long accountId = resolveFundAccountId(f);
        UserServiceClient userClient = userServiceClientProvider.getIfAvailable();
        if (userClient != null && f.getManagerId() != null) {
            try {
                UserServiceClient.EmployeeInfo emp = userClient.getEmployee(f.getManagerId());
                if (emp != null) {
                    managerIme = emp.ime();
                    managerPrezime = emp.prezime();
                }
            } catch (Exception ex) {
                log.debug("Ne mogu da dohvatim ime menadžera {}: {}", f.getManagerId(), ex.getMessage());
            }
        }

        return InvestmentFundDto.builder()
                .id(f.getId()).naziv(f.getNaziv()).opis(f.getOpis())
                .minimumContribution(f.getMinimumContribution())
                .managerId(f.getManagerId())
                .managerIme(managerIme)
                .managerPrezime(managerPrezime)
                .likvidnaSredstva(f.getLikvidnaSredstva())
                .accountId(accountId)
                .accountNumber(f.getAccountNumber())
                .datumKreiranja(f.getDatumKreiranja())
                .totalValue(totalValue)
                .profit(profit)
                .build();
    }

    private Long resolveFundAccountId(InvestmentFund fund) {
        if (fund.getAccountNumber() == null || fund.getAccountNumber().isBlank()) {
            return null;
        }
        AccountServiceClient client = accountServiceClientProvider.getIfAvailable();
        if (client == null) {
            return null;
        }
        try {
            AccountServiceClient.AccountDetails account = client.getByNumber(fund.getAccountNumber());
            return account != null ? account.id() : null;
        } catch (Exception ex) {
            log.debug("Ne mogu da dohvatim accountId za fond {} racun {}: {}",
                    fund.getId(), fund.getAccountNumber(), ex.getMessage());
            return null;
        }
    }

    private ClientFundPositionDto toPositionDto(ClientFundPosition pos) {
        InvestmentFund fund = fundRepository.findById(pos.getFundId()).orElse(null);
        String fundNaziv = fund != null ? fund.getNaziv() : "?";
        String fundOpis = fund != null ? fund.getOpis() : null;
        BigDecimal fundValue = fund != null ? computeFundValue(fund) : BigDecimal.ZERO;
        BigDecimal totalFundInvested = positionRepository.findByFundId(pos.getFundId()).stream()
                .map(ClientFundPosition::getTotalInvested)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal percentageOfFund;
        BigDecimal currentPositionValue;
        if (totalFundInvested.signum() <= 0 || fundValue.signum() <= 0) {
            percentageOfFund = BigDecimal.ZERO;
            currentPositionValue = BigDecimal.ZERO;
        } else {
            BigDecimal denominator = totalFundInvested.max(fundValue);
            percentageOfFund = pos.getTotalInvested()
                    .divide(denominator, 6, RoundingMode.HALF_UP);
            currentPositionValue = percentageOfFund.multiply(fundValue).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal clientProfit = currentPositionValue.subtract(pos.getTotalInvested());

        return ClientFundPositionDto.builder()
                .id(pos.getId())
                .clientId(pos.getClientId())
                .fundId(pos.getFundId())
                .fundNaziv(fundNaziv)
                .fundOpis(fundOpis)
                .fundTotalValue(fundValue)
                .totalInvested(pos.getTotalInvested())
                .percentageOfFund(percentageOfFund)
                .currentPositionValue(currentPositionValue)
                .clientProfit(clientProfit)
                .firstInvestedAt(pos.getFirstInvestedAt())
                .lastModifiedAt(pos.getLastModifiedAt())
                .build();
    }

    private void registerAfterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }

    public record FundSubscribeRequestedEvent(
            Long transactionId, Long clientId, Long fundId, BigDecimal amount,
            String fromAccountNumber, String fundAccountNumber) {}

    public record FundRedeemRequestedEvent(
            Long transactionId, Long clientId, Long fundId, BigDecimal amount,
            String toAccountNumber, String fundAccountNumber, boolean liquidEnough) {}
}
