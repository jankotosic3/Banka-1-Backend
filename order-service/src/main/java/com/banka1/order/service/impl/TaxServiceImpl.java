package com.banka1.order.service.impl;

import com.banka1.order.client.AccountClient;
import com.banka1.order.client.ClientClient;
import com.banka1.order.client.EmployeeClient;
import com.banka1.order.client.ExchangeClient;
import com.banka1.order.client.StockClient;
import com.banka1.order.dto.AccountDetailsDto;
import com.banka1.order.dto.CustomerDto;
import com.banka1.order.dto.CustomerPageResponse;
import com.banka1.order.dto.EmployeeDto;
import com.banka1.order.dto.ExchangeRateDto;
import com.banka1.order.dto.StockListingDto;
import com.banka1.order.dto.TaxDebtResponse;
import com.banka1.order.dto.TaxTrackingRowResponse;
import com.banka1.order.dto.client.PaymentDto;
import com.banka1.order.entity.TaxCharge;
import com.banka1.order.entity.Order;
import com.banka1.order.entity.Transaction;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.TaxChargeStatus;
import com.banka1.order.rabbitmq.OrderNotificationProducer;
import com.banka1.order.repository.ActuaryInfoRepository;
import com.banka1.order.repository.OrderRepository;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.order.repository.TaxChargeRepository;
import com.banka1.order.repository.TransactionRepository;
import com.banka1.order.service.TaxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service implementation for capital gains tax calculation and collection.
 *
 * Handles the monthly tax collection process for all investment trading profits.
 *
 * Key Responsibilities:
 * <ul>
 *   <li>Calculate capital gains tax (15% rate) on sell transactions</li>
 *   <li>Convert profits from foreign currencies to RSD via exchange-service</li>
 *   <li>Settle tax payments to state account via account-service</li>
 *   <li>Track tax debts and payments per user</li>
 *   <li>Ensure idempotent execution (no duplicate charging)</li>
 *   <li>Provide supervisor tax tracking and reporting</li>
 * </ul>
 *
 * Tax Calculation Process:
 * <ol>
 *   <li>Fetch all SELL transactions from previous calendar month</li>
 *   <li>For each sell transaction, calculate profit using cost basis from related buy orders</li>
 *   <li>Apply 15% tax rate to profit</li>
 *   <li>Convert to RSD if transaction was in foreign currency</li>
 *   <li>Create TaxCharge record with PENDING status</li>
 *   <li>Transfer amount to state account</li>
 *   <li>Update TaxCharge status to CHARGED/PAID</li>
 *   <li>Send notification to notification-service</li>
 * </ol>
 *
 * Service Integrations:
 * <ul>
 *   <li>account-service: Verify account details, transfer tax to state account</li>
 *   <li>employee-service: Get employee information</li>
 *   <li>client-service: Get customer information</li>
 *   <li>exchange-service: Currency conversion to RSD</li>
 *   <li>stock-service: Fetch security information</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaxServiceImpl implements TaxService {

    /**
     * Stopa poreza na kapitalnu dobit, defaultno 15% po vazecem zakonu (spec Celina 3).
     * Citana iz banka.tax.capital-gains-rate kako bi se mogla menjati bez deploy-a
     * u slucaju regulatorne promene.
     */
    @org.springframework.beans.factory.annotation.Value("${banka.tax.capital-gains-rate:0.15}")
    private BigDecimal taxRate;

    private static final LocalDateTime HISTORY_START = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final int TRACKING_PAGE_SIZE = 100;

    private final TransactionRepository transactionRepository;
    private final OrderRepository orderRepository;
    private final ActuaryInfoRepository actuaryInfoRepository;
    private final PortfolioRepository portfolioRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AccountClient accountClient;
    private final ClientClient clientClient;
    private final EmployeeClient employeeClient;
    private final ExchangeClient exchangeClient;
    private final StockClient stockClient;
    private final OrderNotificationProducer notificationProducer;
    private final TaxChargeRepository taxChargeRepository;

    @Override
    public void collectMonthlyTax() {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        LocalDate firstDayOfThisMonth = now.withDayOfMonth(1);
        LocalDate firstDayOfPrevMonth = firstDayOfThisMonth.minusMonths(1);
        collectTaxForPeriod(firstDayOfPrevMonth.atStartOfDay(), firstDayOfThisMonth.atStartOfDay());
    }

    @Override
    public void collectMonthlyTaxManually() {
        log.info("Manually triggering monthly tax collection");
        collectMonthlyTax();
    }

    @Override
    public void collectCurrentMonthTax() {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        LocalDate firstDayOfThisMonth = now.withDayOfMonth(1);
        collectTaxForPeriod(firstDayOfThisMonth.atStartOfDay(), now.plusDays(1).atStartOfDay());
    }

    private void collectTaxForPeriod(LocalDateTime start, LocalDateTime end) {
        log.info("Collecting taxes for period {} - {}", start, end);

        for (TaxChargeEntry entry : buildTaxChargeEntries(start, end, null)) {
            TaxCharge reservation = reserveTaxCharge(entry, start, end);
            if (reservation == null) {
                continue;
            }
            boolean debitSucceeded = false;
            try {
                AccountDetailsDto sourceAccount = accountClient.getAccountDetailsById(entry.sourceAccountId());
                AccountDetailsDto governmentAccount = accountClient.getGovernmentBankAccountRsd();
                BigDecimal taxInRsd = convertTaxToRsd(entry.currency(), entry.taxAmount());

                PaymentDto payment = new PaymentDto(
                        sourceAccount.getAccountNumber(),
                        governmentAccount.getAccountNumber(),
                        taxInRsd,
                        taxInRsd,
                        BigDecimal.ZERO,
                        entry.userId()
                );

                var updatedBalances = accountClient.transaction(payment);
                debitSucceeded = true;
                reservation.setTaxAmountRsd(taxInRsd);
                reservation.setStatus(TaxChargeStatus.CHARGED);
                reservation.setChargedAt(LocalDateTime.now());
                taxChargeRepository.save(reservation);

                notificationProducer.sendTaxCollected(createTaxNotificationPayload(entry, taxInRsd, updatedBalances));
                log.info("Collected stock tax {} (RSD {}) for transaction {}.", entry.taxAmount(), taxInRsd, entry.transactionId());
            } catch (Exception ex) {
                handleFailedChargeAttempt(reservation, debitSucceeded);
                log.error("Failed to collect tax for transaction {}", entry.transactionId(), ex);
            }
        }

        collectOtcTaxForPeriod(start, end);
    }

    private void collectOtcTaxForPeriod(LocalDateTime start, LocalDateTime end) {
        AccountDetailsDto governmentAccount = accountClient.getGovernmentBankAccountRsd();

        for (OtcTaxEntry entry : loadExercisedOtcTaxEntries(end)) {
            if (entry.exercisedAt() == null || entry.exercisedAt().isBefore(start)) {
                continue;
            }
            if (taxChargeRepository.existsByOtcContractId(entry.contractId())) {
                log.info("Skipping already processed OTC tax for contract {}.", entry.contractId());
                continue;
            }

            BigDecimal taxInRsd = calculateOtcTaxInRsd(entry);
            if (taxInRsd.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            String sellerAccountNumber = accountClient.getDefaultRsdAccountNumberForOwner(entry.sellerId());
            if (sellerAccountNumber == null) {
                log.warn("Cannot collect OTC tax for contract {}: no RSD account for seller {}.", entry.contractId(), entry.sellerId());
                continue;
            }

            TaxCharge charge = new TaxCharge();
            charge.setSellTransactionId(entry.contractId());
            charge.setBuyTransactionId(entry.contractId());
            charge.setOtcContractId(entry.contractId());
            charge.setUserId(entry.sellerId());
            charge.setListingId(entry.listingId());
            charge.setSourceAccountId(0L);
            charge.setTaxPeriodStart(start);
            charge.setTaxPeriodEnd(end);
            charge.setTaxAmount(taxInRsd);
            charge.setTaxAmountRsd(taxInRsd);
            charge.setStatus(TaxChargeStatus.RESERVED);

            try {
                charge = taxChargeRepository.saveAndFlush(charge);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                log.info("Skipping duplicate OTC tax charge for contract {}.", entry.contractId());
                continue;
            }

            boolean debitSucceeded = false;
            try {
                PaymentDto payment = new PaymentDto(
                        sellerAccountNumber,
                        governmentAccount.getAccountNumber(),
                        taxInRsd,
                        taxInRsd,
                        BigDecimal.ZERO,
                        entry.sellerId()
                );
                accountClient.transaction(payment);
                debitSucceeded = true;
                charge.setStatus(TaxChargeStatus.CHARGED);
                charge.setChargedAt(LocalDateTime.now());
                taxChargeRepository.save(charge);
                log.info("Collected OTC tax {} RSD for contract {}.", taxInRsd, entry.contractId());
            } catch (Exception ex) {
                handleFailedChargeAttempt(charge, debitSucceeded);
                log.error("Failed to collect OTC tax for contract {}", entry.contractId(), ex);
            }
        }
    }

    @Override
    public Page<TaxDebtResponse> getAllDebts(Pageable pageable) {
        log.info("Fetching all tax debts");
        Map<Long, BigDecimal> debtMap = new HashMap<>();

        for (TaxChargeEntry entry : buildTaxChargeEntries(HISTORY_START, LocalDateTime.now(), null)) {
            debtMap.merge(entry.userId(), entry.taxAmount(), BigDecimal::add);
        }

        List<TaxDebtResponse> all = debtMap.entrySet().stream()
                .map(entry -> new TaxDebtResponse(entry.getKey(), entry.getValue()))
                .toList();

        if (pageable.isUnpaged()) {
            return new PageImpl<>(all, pageable, all.size());
        }
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), all.size());
        List<TaxDebtResponse> slice = start >= all.size() ? List.of() : all.subList(start, end);
        return new PageImpl<>(slice, pageable, all.size());
    }

    @Override
    public TaxDebtResponse getUserDebt(Long userId) {
        log.info("Fetching tax debt for user {}", userId);

        BigDecimal totalDebt = buildTaxChargeEntries(HISTORY_START, LocalDateTime.now(), userId).stream()
                .map(TaxChargeEntry::taxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new TaxDebtResponse(userId, totalDebt);
    }

    @Override
    public BigDecimal getCurrentYearPaidTax(Long userId) {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        LocalDateTime yearStart = now.withDayOfYear(1).atStartOfDay();
        LocalDateTime tomorrowStart = now.plusDays(1).atStartOfDay();
        return taxChargeRepository.findByUserIdAndStatus(userId, TaxChargeStatus.CHARGED).stream()
                .filter(c -> c.getChargedAt() != null
                        && !c.getChargedAt().isBefore(yearStart)
                        && c.getChargedAt().isBefore(tomorrowStart))
                .map(c -> c.getTaxAmountRsd() != null ? c.getTaxAmountRsd() : c.getTaxAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public BigDecimal getCurrentMonthUnpaidTax(Long userId) {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        LocalDate firstDayOfMonth = now.withDayOfMonth(1);
        LocalDateTime monthStart = firstDayOfMonth.atStartOfDay();
        LocalDateTime tomorrowStart = now.plusDays(1).atStartOfDay();
        BigDecimal totalTax = calculateTaxForRange(userId, monthStart, tomorrowStart)
                .add(calculateOtcTaxForRange(userId, monthStart, tomorrowStart));
        BigDecimal chargedThisMonth = taxChargeRepository.findByUserIdAndStatus(userId, TaxChargeStatus.CHARGED).stream()
                .filter(c -> c.getChargedAt() != null
                        && !c.getChargedAt().isBefore(monthStart)
                        && c.getChargedAt().isBefore(tomorrowStart))
                .map(c -> c.getTaxAmountRsd() != null ? c.getTaxAmountRsd() : c.getTaxAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return totalTax.subtract(chargedThisMonth).max(BigDecimal.ZERO);
    }

    @Override
    public Page<TaxTrackingRowResponse> getTaxTracking(String userType, String firstName, String lastName, Pageable pageable) {
        Map<Long, TaxTrackingMetrics> metricsByUser = calculateTrackingMetrics();
        List<TaxTrackingRowResponse> all = new ArrayList<>();

        if (userType == null || "CLIENT".equalsIgnoreCase(userType)) {
            all.addAll(loadClientTrackingRows(firstName, lastName, metricsByUser));
        }
        if (userType == null || "ACTUARY".equalsIgnoreCase(userType)) {
            all.addAll(loadActuaryTrackingRows(firstName, lastName, metricsByUser));
        }

        if (pageable.isUnpaged()) {
            return new PageImpl<>(all, pageable, all.size());
        }
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), all.size());
        List<TaxTrackingRowResponse> slice = start >= all.size() ? List.of() : all.subList(start, end);
        return new PageImpl<>(slice, pageable, all.size());
    }

    private BigDecimal calculateTaxForRange(Long userId, LocalDateTime start, LocalDateTime end) {
        return buildTaxChargeEntries(start, end, userId).stream()
                .map(TaxChargeEntry::taxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateOtcTaxForRange(Long userId, LocalDateTime start, LocalDateTime end) {
        return loadExercisedOtcTaxEntries(end).stream()
                .filter(entry -> userId == null || userId.equals(entry.sellerId()))
                .filter(entry -> entry.exercisedAt() != null)
                .filter(entry -> !entry.exercisedAt().isBefore(start))
                .map(this::calculateOtcTaxInRsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<Long, BigDecimal> calculateDebtMapInRsd(Long userIdFilter, LocalDateTime start, LocalDateTime end) {
        Map<Long, BigDecimal> debtMap = new HashMap<>();
        Map<Long, String> currencyCache = new HashMap<>();
        for (TaxChargeEntry entry : buildTaxChargeEntries(start, end, userIdFilter)) {
            BigDecimal taxInRsd = convertTaxEntryToRsd(entry, currencyCache);
            debtMap.merge(entry.userId(), taxInRsd, BigDecimal::add);
        }
        return debtMap;
    }

    private Map<Long, TaxTrackingMetrics> calculateTrackingMetrics() {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        LocalDateTime currentMonthStart = now.withDayOfMonth(1).atStartOfDay();
        LocalDateTime tomorrowStart = now.plusDays(1).atStartOfDay();
        Map<Long, TaxTrackingMetrics> metricsByUser = new HashMap<>();
        Map<Long, String> currencyCache = new HashMap<>();
        Set<TaxChargeKey> chargedKeys = new HashSet<>();

        Set<Long> chargedOtcIds = new HashSet<>();
        for (TaxCharge charge : taxChargeRepository.findAll()) {
            TaxTrackingMetrics metrics = metricsByUser.computeIfAbsent(charge.getUserId(), ignored -> new TaxTrackingMetrics());
            metrics.recordCalculation(charge.getCreatedAt());
            if (charge.getStatus() == TaxChargeStatus.CHARGED) {
                chargedKeys.add(new TaxChargeKey(charge.getSellTransactionId(), charge.getBuyTransactionId()));
                metrics.addPaid(resolveChargeAmountRsd(charge, currencyCache));
                if (charge.getOtcContractId() != null) {
                    chargedOtcIds.add(charge.getOtcContractId());
                }
            } else if (charge.getStatus() == TaxChargeStatus.FAILED) {
                metrics.markFailed();
            }
        }

        for (TaxChargeEntry entry : buildTaxChargeEntries(HISTORY_START, tomorrowStart, null)) {
            BigDecimal taxInRsd = convertTaxEntryToRsd(entry, currencyCache);
            TaxTrackingMetrics metrics = metricsByUser.computeIfAbsent(entry.userId(), ignored -> new TaxTrackingMetrics());
            if (!entry.transactionTimestamp().isBefore(currentMonthStart)) {
                metrics.addCurrentMonthTax(taxInRsd);
            }
            if (!chargedKeys.contains(new TaxChargeKey(entry.transactionId(), entry.buyTransactionId()))) {
                metrics.addDebt(taxInRsd);
            }
        }
        addOtcTrackingMetrics(metricsByUser, currentMonthStart, tomorrowStart, chargedOtcIds);

        return metricsByUser;
    }

    private void addOtcTrackingMetrics(Map<Long, TaxTrackingMetrics> metricsByUser,
                                       LocalDateTime currentMonthStart,
                                       LocalDateTime endExclusive,
                                       Set<Long> chargedOtcIds) {
        for (OtcTaxEntry entry : loadExercisedOtcTaxEntries(endExclusive)) {
            BigDecimal taxInRsd = calculateOtcTaxInRsd(entry);
            if (taxInRsd.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            TaxTrackingMetrics metrics = metricsByUser.computeIfAbsent(entry.sellerId(), ignored -> new TaxTrackingMetrics());
            if (entry.exercisedAt() != null && !entry.exercisedAt().isBefore(currentMonthStart)) {
                metrics.addCurrentMonthTax(taxInRsd);
            }
            if (!chargedOtcIds.contains(entry.contractId())) {
                metrics.addDebt(taxInRsd);
            }
        }
    }

    private BigDecimal calculateOtcTaxInRsd(OtcTaxEntry entry) {
        BigDecimal profit = entry.sellPricePerStock()
                .subtract(entry.averagePurchasePrice())
                .multiply(BigDecimal.valueOf(entry.amount()));
        if (profit.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal tax = profit.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        return convertOtcTaxToRsd(tax, entry);
    }

    private List<OtcTaxEntry> loadExercisedOtcTaxEntries(LocalDateTime endExclusive) {
        String sql = """
                select distinct on (c.id)
                       c.id as contract_id,
                       c.seller_id,
                       c.stock_ticker,
                       c.amount,
                       c.price_per_stock,
                       c.exercised_at,
                       t.listing_id,
                       p.average_purchase_price
                  from option_contracts c
                  join stock_ownership_transfers t
                    on t.seller_id = c.seller_id
                   and t.buyer_id = c.buyer_id
                   and upper(t.stock_ticker) = upper(c.stock_ticker)
                   and t.amount = c.amount
                   and t.status = 'COMPLETED'
                  join portfolio p
                    on p.user_id = c.seller_id
                   and p.listing_id = t.listing_id
                 where c.status = 'EXERCISED'
                   and c.exercised_at is not null
                   and c.exercised_at < ?
                 order by c.id,
                          abs(extract(epoch from (t.created_at - c.exercised_at))) asc
                """;
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> new OtcTaxEntry(
                    rs.getLong("contract_id"),
                    rs.getLong("seller_id"),
                    rs.getLong("listing_id"),
                    rs.getString("stock_ticker"),
                    rs.getInt("amount"),
                    rs.getBigDecimal("price_per_stock"),
                    rs.getBigDecimal("average_purchase_price"),
                    rs.getTimestamp("exercised_at").toLocalDateTime()
            ), endExclusive);
        } catch (Exception ex) {
            log.warn("Unable to load exercised OTC contracts for tax tracking", ex);
            return List.of();
        }
    }

    private BigDecimal convertOtcTaxToRsd(BigDecimal taxAmount, OtcTaxEntry entry) {
        try {
            ExchangeRateDto conversion = exchangeClient.calculateWithoutCommission("USD", "RSD", taxAmount);
            if (conversion == null || conversion.getConvertedAmount() == null) {
                throw new IllegalStateException("Missing converted RSD amount for OTC contract " + entry.contractId());
            }
            return conversion.getConvertedAmount();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to convert OTC tax tracking debt to RSD for contract " + entry.contractId(),
                    ex
            );
        }
    }

    private BigDecimal resolveChargeAmountRsd(TaxCharge charge, Map<Long, String> currencyCache) {
        if (charge.getTaxAmountRsd() != null) {
            return charge.getTaxAmountRsd();
        }
        TaxChargeEntry entry = new TaxChargeEntry(
                charge.getUserId(),
                charge.getListingId(),
                charge.getSellTransactionId(),
                charge.getBuyTransactionId(),
                charge.getSourceAccountId(),
                charge.getTaxAmountRsd() != null ? charge.getTaxAmountRsd() : charge.getTaxAmount(),
                charge.getCreatedAt(),
                "RSD"
        );
        return convertTaxEntryToRsd(entry, currencyCache);
    }

    private TaxTrackingMetrics metricsFor(Map<Long, TaxTrackingMetrics> metricsByUser, Long userId) {
        return metricsByUser.getOrDefault(userId, TaxTrackingMetrics.empty());
    }

    private List<TaxTrackingRowResponse> loadClientTrackingRows(String firstName, String lastName, Map<Long, TaxTrackingMetrics> metricsByUser) {
        List<TaxTrackingRowResponse> rows = new ArrayList<>();
        int page = 0;
        while (true) {
            CustomerPageResponse customers = clientClient.searchCustomers(firstName, lastName, page, TRACKING_PAGE_SIZE);
            if (customers == null || customers.getContent() == null || customers.getContent().isEmpty()) {
                break;
            }
            rows.addAll(customers.getContent().stream()
                    .map(customer -> new TaxTrackingRowResponse(
                            customer.getFirstName(),
                            customer.getLastName(),
                            "CLIENT",
                            metricsFor(metricsByUser, customer.getId()).debt(),
                            metricsFor(metricsByUser, customer.getId()).lastCalculationDate(),
                            metricsFor(metricsByUser, customer.getId()).currentMonthTax(),
                            metricsFor(metricsByUser, customer.getId()).paidTax(),
                            metricsFor(metricsByUser, customer.getId()).status()
                    ))
                    .toList());
            page++;
            if (page >= customers.getTotalPages()) {
                break;
            }
        }
        return rows;
    }

    private List<TaxTrackingRowResponse> loadActuaryTrackingRows(String firstName, String lastName, Map<Long, TaxTrackingMetrics> metricsByUser) {
        List<TaxTrackingRowResponse> rows = new ArrayList<>();
        for (var actuaryInfo : actuaryInfoRepository.findAll()) {
            EmployeeDto employee;
            try {
                employee = employeeClient.getEmployee(actuaryInfo.getEmployeeId());
            } catch (Exception e) {
                log.warn("Failed to fetch employee {} for actuary tax tracking", actuaryInfo.getEmployeeId(), e);
                continue;
            }
            if (employee == null) {
                continue;
            }
            if (firstName != null && !firstName.isBlank()
                    && (employee.getIme() == null || !employee.getIme().toLowerCase().contains(firstName.toLowerCase()))) {
                continue;
            }
            if (lastName != null && !lastName.isBlank()
                    && (employee.getPrezime() == null || !employee.getPrezime().toLowerCase().contains(lastName.toLowerCase()))) {
                continue;
            }
            rows.add(new TaxTrackingRowResponse(
                    employee.getIme(),
                    employee.getPrezime(),
                    "ACTUARY",
                    metricsFor(metricsByUser, actuaryInfo.getEmployeeId()).debt(),
                    metricsFor(metricsByUser, actuaryInfo.getEmployeeId()).lastCalculationDate(),
                    metricsFor(metricsByUser, actuaryInfo.getEmployeeId()).currentMonthTax(),
                    metricsFor(metricsByUser, actuaryInfo.getEmployeeId()).paidTax(),
                    metricsFor(metricsByUser, actuaryInfo.getEmployeeId()).status()
            ));
        }
        return rows;
    }

    private List<TaxChargeEntry> buildTaxChargeEntries(LocalDateTime start, LocalDateTime end, Long userIdFilter) {
        Map<Long, Order> orderCache = new HashMap<>();
        Map<Long, Boolean> stockListingCache = new HashMap<>();
        Map<Long, String> listingCurrencyCache = new HashMap<>();
        List<Transaction> relevantSellTransactions = loadRelevantSellTransactions(start, end, userIdFilter, orderCache, stockListingCache, listingCurrencyCache);
        if (relevantSellTransactions.isEmpty()) {
            return List.of();
        }

        List<Transaction> transactions = loadHistoricalTransactionsForRelevantKeys(end, relevantSellTransactions, orderCache, stockListingCache, listingCurrencyCache);
        if (transactions.isEmpty()) {
            return List.of();
        }

        Map<UserListingKey, Deque<BuyLot>> buyLots = new HashMap<>();
        List<TaxChargeEntry> charges = new ArrayList<>();

        transactions.sort(Comparator
                .comparing(Transaction::getTimestamp)
                .thenComparing((Transaction tx) -> orderDirectionRank(resolveOrder(orderCache, tx.getOrderId())))
                .thenComparing(Transaction::getId, Comparator.nullsLast(Long::compareTo)));

        for (Transaction tx : transactions) {
            try {
                Order order = resolveOrder(orderCache, tx.getOrderId());
                if (order == null || order.getUserId() == null) {
                    continue;
                }
                if (userIdFilter != null && !userIdFilter.equals(order.getUserId())) {
                    continue;
                }
                if (!isStockOrder(order, stockListingCache, listingCurrencyCache)) {
                    continue;
                }
                if (tx.getQuantity() == null || tx.getQuantity() <= 0 || tx.getPricePerUnit() == null) {
                    continue;
                }

                String listingCurrency = listingCurrencyCache.getOrDefault(order.getListingId(), "USD");
                UserListingKey key = new UserListingKey(order.getUserId(), order.getListingId());
                Deque<BuyLot> lots = buyLots.computeIfAbsent(key, ignored -> new ArrayDeque<>());

                if (order.getDirection() == OrderDirection.BUY) {
                    lots.addLast(new BuyLot(tx.getId(), tx.getQuantity(), tx.getPricePerUnit(), order.getAccountId()));
                    continue;
                }
                if (order.getDirection() != OrderDirection.SELL) {
                    continue;
                }

                BigDecimal portfolioAvg = portfolioRepository
                        .findByUserIdAndListingId(order.getUserId(), order.getListingId())
                        .map(p -> p.getAveragePurchasePrice())
                        .orElse(null);
                allocateSellTaxLots(charges, lots, order, tx, start, end, portfolioAvg, listingCurrency);
            } catch (Exception ex) {
                log.error("Error building tax charge entries for transaction {}", tx.getId(), ex);
            }
        }

        return charges;
    }

    private List<Transaction> loadRelevantSellTransactions(
            LocalDateTime start,
            LocalDateTime end,
            Long userIdFilter,
            Map<Long, Order> orderCache,
            Map<Long, Boolean> stockListingCache,
            Map<Long, String> listingCurrencyCache
    ) {
        List<Order> sellOrders = userIdFilter == null
                ? orderRepository.findByDirection(OrderDirection.SELL)
                : orderRepository.findByUserIdAndDirection(userIdFilter, OrderDirection.SELL);
        if (sellOrders.isEmpty()) {
            return List.of();
        }

        sellOrders.forEach(order -> orderCache.put(order.getId(), order));
        List<Long> sellOrderIds = sellOrders.stream()
                .map(Order::getId)
                .toList();

        return transactionRepository.findByOrderIdInAndTimestampBetween(sellOrderIds, start, end).stream()
                .filter(tx -> {
                    Order order = resolveOrder(orderCache, tx.getOrderId());
                    return order != null && isStockOrder(order, stockListingCache, listingCurrencyCache);
                })
                .toList();
    }

    private List<Transaction> loadHistoricalTransactionsForRelevantKeys(
            LocalDateTime end,
            List<Transaction> relevantSellTransactions,
            Map<Long, Order> orderCache,
            Map<Long, Boolean> stockListingCache,
            Map<Long, String> listingCurrencyCache
    ) {
        Map<Long, Set<Long>> listingsByUser = new HashMap<>();
        Set<Long> relevantUserIds = new HashSet<>();

        for (Transaction sellTransaction : relevantSellTransactions) {
            Order sellOrder = resolveOrder(orderCache, sellTransaction.getOrderId());
            if (sellOrder == null || sellOrder.getUserId() == null || sellOrder.getListingId() == null) {
                continue;
            }
            relevantUserIds.add(sellOrder.getUserId());
            listingsByUser.computeIfAbsent(sellOrder.getUserId(), ignored -> new HashSet<>())
                    .add(sellOrder.getListingId());
        }

        if (relevantUserIds.isEmpty()) {
            return List.of();
        }

        List<Order> candidateOrders = relevantUserIds.size() == 1
                ? orderRepository.findByUserId(relevantUserIds.iterator().next())
                : orderRepository.findByUserIdIn(relevantUserIds);
        candidateOrders.forEach(order -> orderCache.put(order.getId(), order));

        List<Long> orderIds = candidateOrders.stream()
                .filter(order -> belongsToRelevantTaxScope(order, listingsByUser))
                .filter(order -> isStockOrder(order, stockListingCache, listingCurrencyCache))
                .map(Order::getId)
                .toList();
        if (orderIds.isEmpty()) {
            return List.of();
        }

        return new ArrayList<>(transactionRepository.findByOrderIdInAndTimestampBefore(orderIds, end));
    }

    private boolean belongsToRelevantTaxScope(Order order, Map<Long, Set<Long>> listingsByUser) {
        if (order == null || order.getUserId() == null || order.getListingId() == null) {
            return false;
        }
        Set<Long> listingIds = listingsByUser.get(order.getUserId());
        return listingIds != null && listingIds.contains(order.getListingId());
    }

    private void allocateSellTaxLots(List<TaxChargeEntry> charges, Deque<BuyLot> lots, Order sellOrder,
                                     Transaction sellTransaction, LocalDateTime start, LocalDateTime end,
                                     BigDecimal portfolioAvgFallback, String listingCurrency) {
        int quantityToMatch = sellTransaction.getQuantity();

        while (quantityToMatch > 0 && !lots.isEmpty()) {
            BuyLot lot = lots.peekFirst();
            int matchedQuantity = Math.min(quantityToMatch, lot.remainingQuantity());
            BigDecimal gainPerShare = sellTransaction.getPricePerUnit().subtract(lot.purchasePricePerUnit());

            if (!sellTransaction.getTimestamp().isBefore(start)
                    && sellTransaction.getTimestamp().isBefore(end)
                    && gainPerShare.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal taxableGain = gainPerShare.multiply(BigDecimal.valueOf(matchedQuantity));
                charges.add(new TaxChargeEntry(
                        sellOrder.getUserId(),
                        sellOrder.getListingId(),
                        sellTransaction.getId(),
                        lot.buyTransactionId(),
                        lot.sourceAccountId(),
                        taxableGain.multiply(taxRate).setScale(2, RoundingMode.HALF_UP),
                        sellTransaction.getTimestamp(),
                        listingCurrency
                ));
            }

            quantityToMatch -= matchedQuantity;
            lot.remainingQuantity(lot.remainingQuantity() - matchedQuantity);
            if (lot.remainingQuantity() == 0) {
                lots.removeFirst();
            }
        }

        if (quantityToMatch > 0 && portfolioAvgFallback != null
                && portfolioAvgFallback.compareTo(BigDecimal.ZERO) > 0) {
            // No buy transaction history — use portfolio average_purchase_price as cost basis.
            // This covers stocks acquired via direct seeding, OTC exercise, or interbank transfer.
            BigDecimal gainPerShare = sellTransaction.getPricePerUnit().subtract(portfolioAvgFallback);
            if (!sellTransaction.getTimestamp().isBefore(start)
                    && sellTransaction.getTimestamp().isBefore(end)
                    && gainPerShare.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal taxableGain = gainPerShare.multiply(BigDecimal.valueOf(quantityToMatch));
                charges.add(new TaxChargeEntry(
                        sellOrder.getUserId(),
                        sellOrder.getListingId(),
                        sellTransaction.getId(),
                        -1L,
                        sellOrder.getAccountId(),
                        taxableGain.multiply(taxRate).setScale(2, RoundingMode.HALF_UP),
                        sellTransaction.getTimestamp(),
                        listingCurrency
                ));
            }
        } else if (quantityToMatch > 0) {
            log.warn("Unable to fully match sold stock quantity for transaction {}. Unmatched quantity: {}", sellTransaction.getId(), quantityToMatch);
        }
    }

    private Order resolveOrder(Map<Long, Order> orderCache, Long orderId) {
        if (orderId == null) {
            return null;
        }
        return orderCache.computeIfAbsent(orderId, id -> orderRepository.findById(id).orElse(null));
    }

    private boolean isStockOrder(Order order, Map<Long, Boolean> stockListingCache, Map<Long, String> listingCurrencyCache) {
        if (order == null || order.getListingId() == null) {
            return false;
        }
        return stockListingCache.computeIfAbsent(order.getListingId(), listingId -> {
            try {
                StockListingDto listing = stockClient.getListing(listingId);
                String currency = listing.getCurrency();
                listingCurrencyCache.put(listingId, currency != null ? currency : "USD");
                return listing.getListingType() == ListingType.STOCK;
            } catch (Exception ex) {
                log.warn("Unable to resolve listing type for listing {} during tax calculation", listingId, ex);
                listingCurrencyCache.putIfAbsent(listingId, "USD");
                return false;
            }
        });
    }

    private BigDecimal convertTaxToRsd(String fromCurrency, BigDecimal taxAmount) {
        if (taxAmount == null) {
            return BigDecimal.ZERO;
        }
        if (fromCurrency == null || "RSD".equalsIgnoreCase(fromCurrency)) {
            return taxAmount;
        }

        try {
            ExchangeRateDto conversion = exchangeClient.calculateWithoutCommission(fromCurrency, "RSD", taxAmount);
            return conversion != null && conversion.getConvertedAmount() != null
                    ? conversion.getConvertedAmount()
                    : taxAmount;
        } catch (Exception ex) {
            log.warn("Exchange failed while converting tax from {} to RSD. Falling back to original amount.", fromCurrency, ex);
            return taxAmount;
        }
    }

    private BigDecimal convertTaxEntryToRsd(TaxChargeEntry entry, Map<Long, String> currencyCache) {
        return convertTaxToRsdStrict(entry.currency(), entry.taxAmount(), entry);
    }

    private BigDecimal convertTaxToRsdStrict(String fromCurrency, BigDecimal taxAmount, TaxChargeEntry entry) {
        if (taxAmount == null) {
            return BigDecimal.ZERO;
        }
        if (fromCurrency == null || fromCurrency.isBlank()) {
            throw new IllegalStateException("Missing account currency for tax tracking entry " + entry.transactionId());
        }
        if ("RSD".equalsIgnoreCase(fromCurrency)) {
            return taxAmount;
        }

        try {
            ExchangeRateDto conversion = exchangeClient.calculateWithoutCommission(fromCurrency, "RSD", taxAmount);
            if (conversion == null || conversion.getConvertedAmount() == null) {
                throw new IllegalStateException("Missing converted RSD amount for tax tracking entry " + entry.transactionId());
            }
            return conversion.getConvertedAmount();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to convert tax tracking debt to RSD for transaction " + entry.transactionId(),
                    ex
            );
        }
    }

    private TaxCharge reserveTaxCharge(TaxChargeEntry entry, LocalDateTime start, LocalDateTime end) {
        if (taxChargeRepository.existsBySellTransactionIdAndBuyTransactionId(entry.transactionId(), entry.buyTransactionId())) {
            log.info("Skipping already processed tax charge for sell transaction {} and buy transaction {}.",
                    entry.transactionId(), entry.buyTransactionId());
            return null;
        }

        TaxCharge taxCharge = new TaxCharge();
        taxCharge.setSellTransactionId(entry.transactionId());
        taxCharge.setBuyTransactionId(entry.buyTransactionId());
        taxCharge.setUserId(entry.userId());
        taxCharge.setListingId(entry.listingId());
        taxCharge.setSourceAccountId(entry.sourceAccountId());
        taxCharge.setTaxPeriodStart(start);
        taxCharge.setTaxPeriodEnd(end);
        taxCharge.setTaxAmount(entry.taxAmount());
        taxCharge.setStatus(TaxChargeStatus.RESERVED);

        try {
            return taxChargeRepository.saveAndFlush(taxCharge);
        } catch (DataIntegrityViolationException ex) {
            log.info("Skipping duplicate tax charge reservation for sell transaction {} and buy transaction {}.",
                    entry.transactionId(), entry.buyTransactionId());
            return null;
        }
    }

    private void handleFailedChargeAttempt(TaxCharge reservation, boolean debitSucceeded) {
        if (reservation == null) {
            return;
        }
        if (!debitSucceeded) {
            taxChargeRepository.delete(reservation);
            return;
        }
        try {
            reservation.setStatus(TaxChargeStatus.CHARGED);
            if (reservation.getChargedAt() == null) {
                reservation.setChargedAt(LocalDateTime.now());
            }
            taxChargeRepository.save(reservation);
        } catch (Exception persistenceEx) {
            log.error("Failed to persist charged tax reservation {} after successful debit; keeping existing reservation state.",
                    reservation.getId(), persistenceEx);
        }
    }

    private Map<String, Object> createTaxNotificationPayload(
            TaxChargeEntry entry,
            BigDecimal taxInRsd,
            com.banka1.order.dto.response.UpdatedBalanceResponseDto updatedBalances
    ) {
        var payload = new HashMap<String, Object>();
        var templateVariables = new HashMap<String, String>();
        templateVariables.put("listingId", String.valueOf(entry.listingId()));
        templateVariables.put("transactionId", String.valueOf(entry.transactionId()));
        templateVariables.put("tax", entry.taxAmount().toPlainString());
        templateVariables.put("taxRsd", taxInRsd.toPlainString());
        if (updatedBalances != null && updatedBalances.getSenderBalance() != null) {
            templateVariables.put("senderBalance", updatedBalances.getSenderBalance().toPlainString());
        }
        if (updatedBalances != null && updatedBalances.getReceiverBalance() != null) {
            templateVariables.put("receiverBalance", updatedBalances.getReceiverBalance().toPlainString());
        }
        payload.put("templateVariables", templateVariables);
        enrichTaxNotificationPayload(payload, entry.userId());
        return payload;
    }

    private void enrichTaxNotificationPayload(Map<String, Object> payload, Long userId) {
        try {
            CustomerDto customer = clientClient.getCustomer(userId);
            if (customer != null) {
                payload.put("username", buildFullName(customer.getFirstName(), customer.getLastName()));
                payload.put("userEmail", customer.getEmail());
                return;
            }
        } catch (Exception ignored) {
            log.debug("User {} not resolved via client-service for tax notification", userId);
        }

        try {
            EmployeeDto employee = employeeClient.getEmployee(userId);
            if (employee != null) {
                payload.put("username", buildFullName(employee.getIme(), employee.getPrezime()));
                payload.put("userEmail", employee.getEmail());
            }
        } catch (Exception ignored) {
            log.debug("User {} not resolved via employee-service for tax notification", userId);
        }
    }

    private String buildFullName(String firstName, String lastName) {
        String safeFirstName = firstName == null ? "" : firstName.trim();
        String safeLastName = lastName == null ? "" : lastName.trim();
        return (safeFirstName + " " + safeLastName).trim();
    }

    private int orderDirectionRank(Order order) {
        if (order == null || order.getDirection() == null) {
            return 2;
        }
        return order.getDirection() == OrderDirection.BUY ? 0 : 1;
    }

    private record UserListingKey(Long userId, Long listingId) {
    }

    private record TaxChargeKey(Long sellTransactionId, Long buyTransactionId) {
    }

    private static final class TaxTrackingMetrics {
        private static final TaxTrackingMetrics EMPTY = new TaxTrackingMetrics();

        private BigDecimal debt = BigDecimal.ZERO;
        private BigDecimal currentMonthTax = BigDecimal.ZERO;
        private BigDecimal paidTax = BigDecimal.ZERO;
        private LocalDateTime lastCalculationDate;
        private boolean failed;

        static TaxTrackingMetrics empty() {
            return EMPTY;
        }

        void addDebt(BigDecimal amount) {
            debt = debt.add(amount == null ? BigDecimal.ZERO : amount);
        }

        void addCurrentMonthTax(BigDecimal amount) {
            currentMonthTax = currentMonthTax.add(amount == null ? BigDecimal.ZERO : amount);
        }

        void addPaid(BigDecimal amount) {
            paidTax = paidTax.add(amount == null ? BigDecimal.ZERO : amount);
        }

        void recordCalculation(LocalDateTime calculatedAt) {
            if (calculatedAt != null && (lastCalculationDate == null || calculatedAt.isAfter(lastCalculationDate))) {
                lastCalculationDate = calculatedAt;
            }
        }

        void markFailed() {
            failed = true;
        }

        BigDecimal debt() {
            return debt;
        }

        BigDecimal currentMonthTax() {
            return currentMonthTax;
        }

        BigDecimal paidTax() {
            return paidTax;
        }

        LocalDateTime lastCalculationDate() {
            return lastCalculationDate;
        }

        String status() {
            if (failed) {
                return "FAILED";
            }
            if (debt.compareTo(BigDecimal.ZERO) > 0 && paidTax.compareTo(BigDecimal.ZERO) > 0) {
                return "PARTIALLY_PAID";
            }
            if (debt.compareTo(BigDecimal.ZERO) > 0) {
                return "PENDING";
            }
            if (paidTax.compareTo(BigDecimal.ZERO) > 0) {
                return "PAID";
            }
            return "ACTIVE";
        }
    }

    private static final class BuyLot {
        private final Long buyTransactionId;
        private int remainingQuantity;
        private final BigDecimal purchasePricePerUnit;
        private final Long sourceAccountId;

        private BuyLot(Long buyTransactionId, int remainingQuantity, BigDecimal purchasePricePerUnit, Long sourceAccountId) {
            this.buyTransactionId = buyTransactionId;
            this.remainingQuantity = remainingQuantity;
            this.purchasePricePerUnit = purchasePricePerUnit;
            this.sourceAccountId = sourceAccountId;
        }

        Long buyTransactionId() {
            return buyTransactionId;
        }

        int remainingQuantity() {
            return remainingQuantity;
        }

        void remainingQuantity(int remainingQuantity) {
            this.remainingQuantity = remainingQuantity;
        }

        BigDecimal purchasePricePerUnit() {
            return purchasePricePerUnit;
        }

        Long sourceAccountId() {
            return sourceAccountId;
        }
    }

    private record TaxChargeEntry(Long userId, Long listingId, Long transactionId, Long buyTransactionId,
                                  Long sourceAccountId, BigDecimal taxAmount, LocalDateTime transactionTimestamp,
                                  String currency) {
    }

    private record OtcTaxEntry(Long contractId, Long sellerId, Long listingId, String ticker, int amount,
                               BigDecimal sellPricePerStock, BigDecimal averagePurchasePrice,
                               LocalDateTime exercisedAt) {
    }
}
