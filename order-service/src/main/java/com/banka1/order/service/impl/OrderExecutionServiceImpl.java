package com.banka1.order.service.impl;

import com.banka1.order.client.AccountClient;
import com.banka1.order.client.EmployeeClient;
import com.banka1.order.client.ExchangeClient;
import com.banka1.order.client.StockClient;
import com.banka1.order.dto.AccountTransactionRequest;
import com.banka1.order.dto.EmployeeDto;
import com.banka1.order.dto.ExchangeRateDto;
import com.banka1.order.dto.StockListingDto;
import com.banka1.order.entity.ActuaryInfo;
import com.banka1.order.entity.Order;
import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.Transaction;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderStatus;
import com.banka1.order.entity.enums.OrderType;
import com.banka1.order.repository.ActuaryInfoRepository;
import com.banka1.order.repository.OrderRepository;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.order.repository.TransactionRepository;
import com.banka1.order.service.OrderExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * Implementation of OrderExecutionService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderExecutionServiceImpl implements OrderExecutionService {

    private static final String LIMIT_CURRENCY = "RSD";
    private static final String USD = "USD";

    private final OrderRepository orderRepository;
    private final PortfolioRepository portfolioRepository;
    private final TransactionRepository transactionRepository;
    private final StockClient stockClient;
    private final AccountClient accountClient;
    private final EmployeeClient employeeClient;
    private final ExchangeClient exchangeClient;
    private final ActuaryInfoRepository actuaryInfoRepository;

    private final Random random = new Random();

    @Override
    @Async
    public void executeOrderAsync(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        while (order != null && order.getStatus() == OrderStatus.APPROVED && order.getRemainingPortions() > 0 && !order.getIsDone()) {
            executeOrderPortion(order);
            order = orderRepository.findById(orderId).orElse(null);
            if (order == null || order.getStatus() != OrderStatus.APPROVED || order.getIsDone()) {
                break;
            }

            long delayMillis = calculateExecutionDelay(order);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    @Transactional
    public void executeOrderPortion(Order order) {
        if (order.getRemainingPortions() <= 0 || order.getIsDone() || order.getStatus() != OrderStatus.APPROVED) {
            return;
        }

        StockListingDto listing = stockClient.getListing(order.getListingId());
        if (!activateIfEligible(order, listing)) {
            return;
        }

        Integer executableCapacity = currentExecutableCapacity(order, listing);
        if (executableCapacity <= 0) {
            return;
        }
        if (Boolean.TRUE.equals(order.getAllOrNone()) && executableCapacity < order.getRemainingPortions()) {
            return;
        }

        int quantityToExecute = determineExecutionQuantity(order, executableCapacity);
        BigDecimal executionPricePerUnit = calculateExecutionPricePerUnit(order, listing);
        BigDecimal grossChunkAmount = executionPricePerUnit
                .multiply(BigDecimal.valueOf(order.getContractSize()))
                .multiply(BigDecimal.valueOf(quantityToExecute));
        BigDecimal commission = calculateCommission(orderPricingFamily(order.getOrderType()), grossChunkAmount, listing.getCurrency());

        createTransaction(order, quantityToExecute, executionPricePerUnit, grossChunkAmount, commission);
        updatePortfolio(order, listing, quantityToExecute, executionPricePerUnit);
        transferFunds(order, listing.getCurrency(), grossChunkAmount);
        updateActuaryLimit(order, listing.getCurrency(), grossChunkAmount);

        order.setRemainingPortions(order.getRemainingPortions() - quantityToExecute);
        if (order.getRemainingPortions() == 0) {
            order.setIsDone(true);
            order.setStatus(OrderStatus.DONE);
        }
        orderRepository.save(order);
    }

    private boolean activateIfEligible(Order order, StockListingDto listing) {
        if (order.getOrderType() == OrderType.STOP) {
            boolean activated = order.getDirection() == OrderDirection.BUY
                    ? listing.getAsk().compareTo(order.getStopValue()) > 0
                    : listing.getBid().compareTo(order.getStopValue()) < 0;
            if (activated) {
                order.setOrderType(OrderType.MARKET);
                orderRepository.save(order);
            }
            return activated;
        }
        if (order.getOrderType() == OrderType.STOP_LIMIT) {
            boolean activated = order.getDirection() == OrderDirection.BUY
                    ? listing.getAsk().compareTo(order.getStopValue()) >= 0
                    : listing.getBid().compareTo(order.getStopValue()) < 0;
            if (activated) {
                order.setOrderType(OrderType.LIMIT);
                orderRepository.save(order);
            }
            return activated;
        }
        return isExecutableAtCurrentMarket(order, listing);
    }

    private boolean isExecutableAtCurrentMarket(Order order, StockListingDto listing) {
        if (order.getOrderType() == OrderType.MARKET) {
            return true;
        }
        if (order.getOrderType() == OrderType.LIMIT && order.getDirection() == OrderDirection.BUY) {
            return listing.getAsk().compareTo(order.getLimitValue()) <= 0;
        }
        if (order.getOrderType() == OrderType.LIMIT) {
            return listing.getBid().compareTo(order.getLimitValue()) >= 0;
        }
        return false;
    }

    private Integer currentExecutableCapacity(Order order, StockListingDto listing) {
        long volume = listing.getVolume() == null ? order.getRemainingPortions() : listing.getVolume();
        return (int) Math.max(0L, Math.min(volume, order.getRemainingPortions().longValue()));
    }

    private int determineExecutionQuantity(Order order, Integer executableCapacity) {
        if (Boolean.TRUE.equals(order.getAllOrNone())) {
            return order.getRemainingPortions();
        }
        return random.nextInt(executableCapacity) + 1;
    }

    private BigDecimal calculateExecutionPricePerUnit(Order order, StockListingDto listing) {
        return switch (order.getOrderType()) {
            case MARKET -> order.getDirection() == OrderDirection.BUY ? listing.getAsk() : listing.getBid();
            case LIMIT -> order.getDirection() == OrderDirection.BUY
                    ? order.getLimitValue().min(listing.getAsk())
                    : order.getLimitValue().max(listing.getBid());
            case STOP, STOP_LIMIT -> throw new IllegalStateException("Stop-family orders must be activated before execution");
        };
    }

    private void createTransaction(Order order, int quantity, BigDecimal executionPricePerUnit, BigDecimal grossChunkAmount, BigDecimal commission) {
        Transaction transaction = new Transaction();
        transaction.setOrderId(order.getId());
        transaction.setQuantity(quantity);
        transaction.setPricePerUnit(executionPricePerUnit);
        transaction.setTotalPrice(grossChunkAmount);
        transaction.setCommission(commission);
        transaction.setTimestamp(LocalDateTime.now());
        transactionRepository.save(transaction);
    }

    private void updatePortfolio(Order order, StockListingDto listing, int quantity, BigDecimal executionPricePerUnit) {
        Portfolio portfolio = portfolioRepository.findByUserIdAndListingId(order.getUserId(), order.getListingId()).orElse(null);

        if (order.getDirection() == OrderDirection.BUY) {
            if (portfolio == null) {
                portfolio = new Portfolio();
                portfolio.setUserId(order.getUserId());
                portfolio.setListingId(order.getListingId());
                portfolio.setListingType(listing.getListingType() == null ? ListingType.STOCK : listing.getListingType());
                portfolio.setQuantity(quantity);
                portfolio.setAveragePurchasePrice(executionPricePerUnit);
            } else {
                BigDecimal totalValue = portfolio.getAveragePurchasePrice()
                        .multiply(BigDecimal.valueOf(portfolio.getQuantity()))
                        .add(executionPricePerUnit.multiply(BigDecimal.valueOf(quantity)));
                int newQuantity = portfolio.getQuantity() + quantity;
                portfolio.setQuantity(newQuantity);
                portfolio.setAveragePurchasePrice(totalValue.divide(BigDecimal.valueOf(newQuantity), 4, RoundingMode.HALF_UP));
            }
            portfolioRepository.save(portfolio);
            return;
        }

        if (portfolio == null || portfolio.getQuantity() < quantity) {
            throw new IllegalStateException("Cannot execute sell order without owned quantity");
        }

        portfolio.setQuantity(portfolio.getQuantity() - quantity);
        if (portfolio.getQuantity() == 0) {
            portfolioRepository.delete(portfolio);
        } else {
            portfolioRepository.save(portfolio);
        }
    }

    private void transferFunds(Order order, String currency, BigDecimal amount) {
        EmployeeDto bankAccount = employeeClient.getBankAccount(currency);
        AccountTransactionRequest request = new AccountTransactionRequest();
        request.setAmount(amount);
        request.setCurrency(currency);
        request.setDescription("Order execution");
        boolean actuaryOrder = actuaryInfoRepository.findByEmployeeId(order.getUserId()).isPresent();

        if (order.getDirection() == OrderDirection.BUY) {
            request.setFromAccountId(resolveDebitAccount(order, bankAccount, actuaryOrder));
            request.setToAccountId(resolveCreditAccount(order, bankAccount, actuaryOrder));
        } else {
            request.setFromAccountId(bankAccount.getId());
            request.setToAccountId(order.getAccountId());
        }

        if (request.getFromAccountId() != null && request.getFromAccountId().equals(request.getToAccountId())) {
            throw new IllegalStateException("Transfer source and destination must differ");
        }

        accountClient.transfer(request);
    }

    private Long resolveDebitAccount(Order order, EmployeeDto bankAccount, boolean actuaryOrder) {
        if (actuaryOrder) {
            return bankAccount.getId();
        }
        return order.getAccountId();
    }

    private Long resolveCreditAccount(Order order, EmployeeDto bankAccount, boolean actuaryOrder) {
        if (actuaryOrder) {
            return order.getAccountId();
        }
        return bankAccount.getId();
    }

    private void updateActuaryLimit(Order order, String currency, BigDecimal amount) {
        ActuaryInfo actuaryInfo = actuaryInfoRepository.findByEmployeeId(order.getUserId()).orElse(null);
        if (actuaryInfo == null) {
            return;
        }
        BigDecimal converted = convertAmount(currency, LIMIT_CURRENCY, amount);
        BigDecimal usedLimit = actuaryInfo.getUsedLimit() == null ? BigDecimal.ZERO : actuaryInfo.getUsedLimit();
        actuaryInfo.setUsedLimit(usedLimit.add(converted));
        actuaryInfoRepository.save(actuaryInfo);
    }

    private long calculateExecutionDelay(Order order) {
        StockListingDto listing = stockClient.getListing(order.getListingId());
        long volume = listing.getVolume() == null || listing.getVolume() <= 0 ? 1L : listing.getVolume();
        double maxSeconds = (24d * 60d) / (volume / (double) Math.max(1, order.getRemainingPortions()));
        double delaySeconds = random.nextDouble() * maxSeconds;
        if (Boolean.TRUE.equals(order.getAfterHours())) {
            delaySeconds += 30d * 60d;
        }
        return (long) (delaySeconds * 1000);
    }

    private BigDecimal calculateCommission(OrderType orderType, BigDecimal baseAmount, String currency) {
        BigDecimal rate = isMarketFamily(orderType) ? new BigDecimal("0.14") : new BigDecimal("0.24");
        BigDecimal capUsd = isMarketFamily(orderType) ? new BigDecimal("7") : new BigDecimal("12");
        BigDecimal cap = convertAmount(USD, currency, capUsd);
        return baseAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP).min(cap);
    }

    private boolean isMarketFamily(OrderType orderType) {
        return orderType == OrderType.MARKET || orderType == OrderType.STOP;
    }

    private OrderType orderPricingFamily(OrderType orderType) {
        return orderType == OrderType.STOP_LIMIT ? OrderType.LIMIT : orderType;
    }

    private BigDecimal convertAmount(String fromCurrency, String toCurrency, BigDecimal amount) {
        if (amount == null || fromCurrency == null || toCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return amount;
        }
        ExchangeRateDto conversion = exchangeClient.calculate(fromCurrency, toCurrency, amount);
        return conversion.getConvertedAmount() == null ? amount : conversion.getConvertedAmount();
    }
}
