package com.banka1.order.service.impl;

import com.banka1.order.client.AccountClient;
import com.banka1.order.client.EmployeeClient;
import com.banka1.order.client.ExchangeClient;
import com.banka1.order.client.StockClient;
import com.banka1.order.dto.AccountDetailsDto;
import com.banka1.order.dto.AccountTransactionRequest;
import com.banka1.order.dto.AuthenticatedUser;
import com.banka1.order.dto.CreateBuyOrderRequest;
import com.banka1.order.dto.CreateSellOrderRequest;
import com.banka1.order.dto.EmployeeDto;
import com.banka1.order.dto.ExchangeRateDto;
import com.banka1.order.dto.ExchangeStatusDto;
import com.banka1.order.dto.OrderResponse;
import com.banka1.order.dto.StockListingDto;
import com.banka1.order.entity.ActuaryInfo;
import com.banka1.order.entity.Order;
import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderStatus;
import com.banka1.order.entity.enums.OrderType;
import com.banka1.order.repository.ActuaryInfoRepository;
import com.banka1.order.repository.OrderRepository;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.order.service.OrderCreationService;
import com.banka1.order.service.OrderExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Implementation of OrderCreationService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderCreationServiceImpl implements OrderCreationService {

    static final long NO_APPROVAL_REQUIRED = -1L;
    static final long SYSTEM_APPROVAL = -2L;
    private static final String LIMIT_CURRENCY = "RSD";
    private static final String USD = "USD";

    private final OrderRepository orderRepository;
    private final ActuaryInfoRepository actuaryInfoRepository;
    private final PortfolioRepository portfolioRepository;
    private final StockClient stockClient;
    private final AccountClient accountClient;
    private final EmployeeClient employeeClient;
    private final ExchangeClient exchangeClient;
    private final OrderExecutionService orderExecutionService;

    @Override
    @Transactional
    public OrderResponse createBuyOrder(AuthenticatedUser user, CreateBuyOrderRequest request) {
        validateBuyOrderRequest(request);

        StockListingDto listing = stockClient.getListing(request.getListingId());
        ExchangeStatusDto exchangeStatus = stockClient.getExchangeStatus(listing.getExchangeId());
        OrderType orderType = determineOrderType(request.getLimitValue(), request.getStopValue());
        BigDecimal approximatePrice = calculateApproximatePrice(orderType, OrderDirection.BUY, listing, request.getQuantity(),
                request.getLimitValue(), request.getStopValue());
        BigDecimal fee = calculateFee(orderType, approximatePrice, listing.getCurrency());

        Order order = buildBaseOrder(user.userId(), request.getListingId(), orderType, request.getQuantity(), listing,
                request.getLimitValue(), request.getStopValue(), OrderDirection.BUY, request.getAllOrNone(),
                request.getMargin(), request.getAccountId(), isAfterHoursOrClosed(exchangeStatus));
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setApprovedBy(null);

        order = orderRepository.save(order);
        return mapToResponse(order, approximatePrice, fee);
    }

    @Override
    @Transactional
    public OrderResponse createSellOrder(AuthenticatedUser user, CreateSellOrderRequest request) {
        validateSellOrderRequest(request);
        ensurePortfolioOwnership(user.userId(), request.getListingId(), request.getQuantity());

        StockListingDto listing = stockClient.getListing(request.getListingId());
        ExchangeStatusDto exchangeStatus = stockClient.getExchangeStatus(listing.getExchangeId());
        OrderType orderType = determineOrderType(request.getLimitValue(), request.getStopValue());
        BigDecimal approximatePrice = calculateApproximatePrice(orderType, OrderDirection.SELL, listing, request.getQuantity(),
                request.getLimitValue(), request.getStopValue());
        BigDecimal fee = calculateFee(orderType, approximatePrice, listing.getCurrency());

        Order order = buildBaseOrder(user.userId(), request.getListingId(), orderType, request.getQuantity(), listing,
                request.getLimitValue(), request.getStopValue(), OrderDirection.SELL, request.getAllOrNone(),
                request.getMargin(), request.getAccountId(), isAfterHoursOrClosed(exchangeStatus));
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setApprovedBy(null);

        order = orderRepository.save(order);
        return mapToResponse(order, approximatePrice, fee);
    }

    @Override
    @Transactional
    public OrderResponse confirmOrder(AuthenticatedUser user, Long orderId) {
        Order order = getOwnedOrder(user.userId(), orderId);
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            throw new IllegalStateException("Only draft orders can be confirmed");
        }

        StockListingDto listing = stockClient.getListing(order.getListingId());
        ExchangeStatusDto exchangeStatus = stockClient.getExchangeStatus(listing.getExchangeId());
        order.setAfterHours(isAfterHoursOrClosed(exchangeStatus));

        BigDecimal approximatePrice = calculateApproximatePrice(order.getOrderType(), order.getDirection(), listing,
                order.getQuantity(), order.getLimitValue(), order.getStopValue());
        BigDecimal fee = calculateFee(orderPricingFamily(order.getOrderType()), approximatePrice, listing.getCurrency());

        if (hasPastSettlementDate(listing)) {
            order.setStatus(OrderStatus.DECLINED);
            order.setApprovedBy(SYSTEM_APPROVAL);
            order = orderRepository.save(order);
            return mapToResponse(order, approximatePrice, fee);
        }

        Long fundingAccountId = determineFundingAccountId(user.userId(), order.getAccountId(), listing.getCurrency());
        if (Boolean.TRUE.equals(order.getMargin())) {
            checkMarginRequirements(user, fundingAccountId, listing, order.getQuantity());
        } else if (order.getDirection() == OrderDirection.BUY) {
            checkFunds(fundingAccountId, approximatePrice.add(fee));
        }

        transferFee(order.getAccountId(), fee, listing.getCurrency());

        OrderStatus finalStatus = determineOrderStatus(user.userId(), approximatePrice, listing.getCurrency());
        order.setStatus(finalStatus);
        order.setApprovedBy(finalStatus == OrderStatus.APPROVED ? NO_APPROVAL_REQUIRED : null);
        order = orderRepository.save(order);

        if (finalStatus == OrderStatus.APPROVED) {
            orderExecutionService.executeOrderAsync(order.getId());
        }
        return mapToResponse(order, approximatePrice, fee);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(AuthenticatedUser user, Long orderId) {
        Order order = getOwnedOrder(user.userId(), orderId);
        if (order.getStatus() == OrderStatus.DONE || order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.DECLINED) {
            throw new IllegalStateException("Order can no longer be cancelled");
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setIsDone(true);
        order = orderRepository.save(order);

        StockListingDto listing = stockClient.getListing(order.getListingId());
        return mapToResponse(order, calculateApproximatePrice(order.getOrderType(), order.getDirection(), listing,
                order.getQuantity(), order.getLimitValue(), order.getStopValue()),
                calculateFee(orderPricingFamily(order.getOrderType()),
                        calculateApproximatePrice(order.getOrderType(), order.getDirection(), listing, order.getQuantity(),
                                order.getLimitValue(), order.getStopValue()),
                        listing.getCurrency()));
    }

    @Override
    @Transactional
    public OrderResponse approveOrder(Long supervisorId, Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("Only pending orders can be approved");
        }

        StockListingDto listing = stockClient.getListing(order.getListingId());
        if (hasPastSettlementDate(listing)) {
            throw new IllegalStateException("Orders with past settlement date can only be declined");
        }

        order.setStatus(OrderStatus.APPROVED);
        order.setApprovedBy(supervisorId);
        order = orderRepository.save(order);
        orderExecutionService.executeOrderAsync(order.getId());

        BigDecimal approximatePrice = calculateApproximatePrice(order.getOrderType(), order.getDirection(), listing,
                order.getQuantity(), order.getLimitValue(), order.getStopValue());
        return mapToResponse(order, approximatePrice, calculateFee(orderPricingFamily(order.getOrderType()), approximatePrice, listing.getCurrency()));
    }

    @Override
    @Transactional
    public OrderResponse declineOrder(Long supervisorId, Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("Only pending orders can be declined");
        }

        order.setStatus(OrderStatus.DECLINED);
        order.setApprovedBy(supervisorId);
        order = orderRepository.save(order);

        StockListingDto listing = stockClient.getListing(order.getListingId());
        BigDecimal approximatePrice = calculateApproximatePrice(order.getOrderType(), order.getDirection(), listing,
                order.getQuantity(), order.getLimitValue(), order.getStopValue());
        return mapToResponse(order, approximatePrice, calculateFee(orderPricingFamily(order.getOrderType()), approximatePrice, listing.getCurrency()));
    }

    private Order buildBaseOrder(Long userId, Long listingId, OrderType orderType, Integer quantity, StockListingDto listing,
                                 BigDecimal limitValue, BigDecimal stopValue, OrderDirection direction, Boolean allOrNone,
                                 Boolean margin, Long accountId, boolean afterHours) {
        Order order = new Order();
        order.setUserId(userId);
        order.setListingId(listingId);
        order.setOrderType(orderType);
        order.setQuantity(quantity);
        order.setContractSize(listing.getContractSize());
        order.setPricePerUnit(getReferencePricePerUnit(orderType, direction, listing, limitValue, stopValue));
        order.setLimitValue(limitValue);
        order.setStopValue(stopValue);
        order.setDirection(direction);
        order.setIsDone(false);
        order.setRemainingPortions(quantity);
        order.setAfterHours(afterHours);
        order.setAllOrNone(Boolean.TRUE.equals(allOrNone));
        order.setMargin(Boolean.TRUE.equals(margin));
        order.setAccountId(accountId);
        return order;
    }

    private void validateBuyOrderRequest(CreateBuyOrderRequest request) {
        validateCommonRequest(request.getListingId(), request.getQuantity(), request.getAccountId(),
                request.getLimitValue(), request.getStopValue());
    }

    private void validateSellOrderRequest(CreateSellOrderRequest request) {
        validateCommonRequest(request.getListingId(), request.getQuantity(), request.getAccountId(),
                request.getLimitValue(), request.getStopValue());
    }

    private void validateCommonRequest(Long listingId, Integer quantity, Long accountId, BigDecimal limitValue, BigDecimal stopValue) {
        if (listingId == null || quantity == null || quantity <= 0 || accountId == null) {
            throw new IllegalArgumentException("Invalid request parameters");
        }
        if (limitValue != null && limitValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Limit value must be positive");
        }
        if (stopValue != null && stopValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Stop value must be positive");
        }
    }

    private Order getOwnedOrder(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Order not found"));
        if (!order.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Order does not belong to the authenticated user");
        }
        return order;
    }

    private void ensurePortfolioOwnership(Long userId, Long listingId, Integer requestedQuantity) {
        Portfolio portfolio = portfolioRepository.findByUserIdAndListingId(userId, listingId)
                .orElseThrow(() -> new IllegalArgumentException("Portfolio position not found"));
        if (portfolio.getQuantity() < requestedQuantity) {
            throw new IllegalArgumentException("Insufficient portfolio quantity");
        }
    }

    private OrderType determineOrderType(BigDecimal limitValue, BigDecimal stopValue) {
        if (limitValue == null && stopValue == null) {
            return OrderType.MARKET;
        }
        if (limitValue != null && stopValue == null) {
            return OrderType.LIMIT;
        }
        if (limitValue == null) {
            return OrderType.STOP;
        }
        return OrderType.STOP_LIMIT;
    }

    private BigDecimal calculateApproximatePrice(OrderType orderType, OrderDirection direction, StockListingDto listing, Integer quantity,
                                                 BigDecimal limitValue, BigDecimal stopValue) {
        BigDecimal pricePerUnit = getReferencePricePerUnit(orderType, direction, listing, limitValue, stopValue);
        return pricePerUnit.multiply(BigDecimal.valueOf(listing.getContractSize())).multiply(BigDecimal.valueOf(quantity));
    }

    private BigDecimal getReferencePricePerUnit(OrderType orderType, OrderDirection direction, StockListingDto listing,
                                                BigDecimal limitValue, BigDecimal stopValue) {
        return switch (orderType) {
            case MARKET -> direction == OrderDirection.BUY ? listing.getAsk() : listing.getBid();
            case LIMIT, STOP_LIMIT -> limitValue;
            case STOP -> stopValue;
        };
    }

    private OrderStatus determineOrderStatus(Long userId, BigDecimal approximatePrice, String currency) {
        ActuaryInfo actuaryInfo = actuaryInfoRepository.findByEmployeeId(userId).orElse(null);
        if (actuaryInfo == null) {
            return OrderStatus.APPROVED;
        }

        BigDecimal orderAmountInLimitCurrency = convertAmount(currency, LIMIT_CURRENCY, approximatePrice);
        BigDecimal limit = actuaryInfo.getLimit();
        BigDecimal usedLimit = actuaryInfo.getUsedLimit() == null ? BigDecimal.ZERO : actuaryInfo.getUsedLimit();
        boolean exhausted = limit != null && usedLimit.compareTo(limit) >= 0;
        boolean exceeds = limit != null && usedLimit.add(orderAmountInLimitCurrency).compareTo(limit) > 0;
        if (Boolean.TRUE.equals(actuaryInfo.getNeedApproval()) || exhausted || exceeds) {
            return OrderStatus.PENDING;
        }
        return OrderStatus.APPROVED;
    }

    private void checkMarginRequirements(AuthenticatedUser user, Long fundingAccountId, StockListingDto listing, Integer quantity) {
        if (!user.hasMarginPermission()) {
            throw new IllegalArgumentException("User does not have margin permission");
        }

        BigDecimal initialMarginCost = calculateInitialMarginCost(listing, quantity);
        AccountDetailsDto account = accountClient.getAccountDetails(fundingAccountId);
        BigDecimal availableCredit = account.getAvailableCredit() == null ? BigDecimal.ZERO : account.getAvailableCredit();
        BigDecimal balance = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
        boolean hasCredit = availableCredit.compareTo(initialMarginCost) > 0;
        boolean hasFunds = balance.compareTo(initialMarginCost) > 0;
        if (!hasCredit && !hasFunds) {
            throw new IllegalArgumentException("Margin requirements are not satisfied");
        }
    }

    private BigDecimal calculateInitialMarginCost(StockListingDto listing, Integer quantity) {
        BigDecimal price = listing.getPrice();
        BigDecimal maintenanceMargin;
        if (listing.getMaintenanceMargin() != null) {
            maintenanceMargin = listing.getMaintenanceMargin();
        } else {
            ListingType listingType = listing.getListingType() == null ? ListingType.STOCK : listing.getListingType();
            maintenanceMargin = switch (listingType) {
                case STOCK -> price.multiply(new BigDecimal("0.50"));
                case FOREX, FUTURES -> BigDecimal.valueOf(listing.getContractSize()).multiply(price).multiply(new BigDecimal("0.10"));
                case OPTION -> BigDecimal.valueOf(listing.getContractSize())
                        .multiply(resolveOptionUnderlyingPrice(listing))
                        .multiply(new BigDecimal("0.50"));
            };
        }

        return maintenanceMargin.multiply(new BigDecimal("1.10"))
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveOptionUnderlyingPrice(StockListingDto listing) {
        return listing.getUnderlyingPrice() != null ? listing.getUnderlyingPrice() : listing.getPrice();
    }

    private void checkFunds(Long accountId, BigDecimal totalAmount) {
        AccountDetailsDto account = accountClient.getAccountDetails(accountId);
        BigDecimal balance = account.getBalance() == null ? BigDecimal.ZERO : account.getBalance();
        if (balance.compareTo(totalAmount) < 0) {
            throw new IllegalArgumentException("Insufficient funds");
        }
    }

    private BigDecimal calculateFee(OrderType orderType, BigDecimal approximatePrice, String currency) {
        BigDecimal rate = isMarketFamily(orderType) ? new BigDecimal("0.14") : new BigDecimal("0.24");
        BigDecimal maxFeeUsd = isMarketFamily(orderType) ? new BigDecimal("7") : new BigDecimal("12");
        BigDecimal maxFee = convertAmount(USD, currency, maxFeeUsd);
        BigDecimal fee = approximatePrice.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return fee.min(maxFee);
    }

    private boolean isMarketFamily(OrderType orderType) {
        return orderType == OrderType.MARKET || orderType == OrderType.STOP;
    }

    private OrderType orderPricingFamily(OrderType orderType) {
        return orderType == OrderType.STOP_LIMIT ? OrderType.LIMIT : orderType;
    }

    private void transferFee(Long accountId, BigDecimal fee, String currency) {
        EmployeeDto bankAccount = employeeClient.getBankAccount(currency);
        AccountTransactionRequest transferRequest = new AccountTransactionRequest();
        transferRequest.setFromAccountId(accountId);
        transferRequest.setToAccountId(bankAccount.getId());
        transferRequest.setAmount(fee);
        transferRequest.setCurrency(currency);
        transferRequest.setDescription("Order fee");
        accountClient.transfer(transferRequest);
    }

    private boolean isAfterHoursOrClosed(ExchangeStatusDto exchangeStatus) {
        return exchangeStatus.isAfterHours() || Boolean.TRUE.equals(exchangeStatus.getClosed());
    }

    private boolean hasPastSettlementDate(StockListingDto listing) {
        LocalDate settlementDate = listing.getSettlementDate();
        return settlementDate != null && settlementDate.isBefore(LocalDate.now());
    }

    private Long determineFundingAccountId(Long userId, Long selectedAccountId, String currency) {
        if (actuaryInfoRepository.findByEmployeeId(userId).isPresent()) {
            return employeeClient.getBankAccount(currency).getId();
        }
        return selectedAccountId;
    }

    private BigDecimal convertAmount(String fromCurrency, String toCurrency, BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        if (fromCurrency == null || toCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return amount;
        }
        ExchangeRateDto conversion = exchangeClient.calculate(fromCurrency, toCurrency, amount);
        return conversion.getConvertedAmount() == null ? amount : conversion.getConvertedAmount();
    }

    private OrderResponse mapToResponse(Order order, BigDecimal approximatePrice, BigDecimal fee) {
        OrderResponse response = new OrderResponse();
        response.setId(order.getId());
        response.setUserId(order.getUserId());
        response.setListingId(order.getListingId());
        response.setOrderType(order.getOrderType());
        response.setQuantity(order.getQuantity());
        response.setContractSize(order.getContractSize());
        response.setPricePerUnit(order.getPricePerUnit());
        response.setLimitValue(order.getLimitValue());
        response.setStopValue(order.getStopValue());
        response.setDirection(order.getDirection());
        response.setStatus(order.getStatus());
        response.setApprovedBy(order.getApprovedBy());
        response.setIsDone(order.getIsDone());
        response.setLastModification(order.getLastModification());
        response.setRemainingPortions(order.getRemainingPortions());
        response.setAfterHours(order.getAfterHours());
        response.setAllOrNone(order.getAllOrNone());
        response.setMargin(order.getMargin());
        response.setAccountId(order.getAccountId());
        response.setApproximatePrice(approximatePrice);
        response.setFee(fee);
        return response;
    }
}
