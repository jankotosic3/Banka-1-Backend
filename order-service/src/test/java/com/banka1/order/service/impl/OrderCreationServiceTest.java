package com.banka1.order.service.impl;

import com.banka1.order.client.AccountClient;
import com.banka1.order.client.EmployeeClient;
import com.banka1.order.client.ExchangeClient;
import com.banka1.order.client.StockClient;
import com.banka1.order.dto.AccountDetailsDto;
import com.banka1.order.dto.AccountTransactionRequest;
import com.banka1.order.dto.AuthenticatedUser;
import com.banka1.order.dto.BankAccountDto;
import com.banka1.order.dto.CreateBuyOrderRequest;
import com.banka1.order.dto.CreateSellOrderRequest;
import com.banka1.order.dto.EmployeeDto;
import com.banka1.order.dto.ExchangeRateDto;
import com.banka1.order.dto.ExchangeStatusDto;
import com.banka1.order.dto.OrderNotificationPayload;
import com.banka1.order.dto.OrderOverviewResponse;
import com.banka1.order.dto.OrderResponse;
import com.banka1.order.dto.StockListingDto;
import com.banka1.order.entity.ActuaryInfo;
import com.banka1.order.entity.Order;
import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderOverviewStatusFilter;
import com.banka1.order.entity.enums.OrderStatus;
import com.banka1.order.entity.enums.OrderType;
import com.banka1.order.exception.BusinessConflictException;
import com.banka1.order.exception.ResourceNotFoundException;
import com.banka1.order.rabbitmq.OrderNotificationProducer;
import com.banka1.order.repository.ActuaryInfoRepository;
import com.banka1.order.repository.OrderRepository;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.order.service.OrderExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCreationServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ActuaryInfoRepository actuaryInfoRepository;
    @Mock
    private PortfolioRepository portfolioRepository;
    @Mock
    private StockClient stockClient;
    @Mock
    private AccountClient accountClient;
    @Mock
    private EmployeeClient employeeClient;
    @Mock
    private ExchangeClient exchangeClient;
    @Mock
    private OrderExecutionService orderExecutionService;
    @Mock
    private OrderNotificationProducer orderNotificationProducer;

    @InjectMocks
    private OrderCreationServiceImpl service;

    private AuthenticatedUser clientUser;
    private AuthenticatedUser marginClient;
    private AuthenticatedUser actuaryUser;
    private CreateBuyOrderRequest buyRequest;
    private CreateSellOrderRequest sellRequest;
    private StockListingDto listing;
    private ExchangeStatusDto exchangeStatus;
    private AccountDetailsDto accountDetails;
    private BankAccountDto bankAccount;
    private EmployeeDto employee;
    private AtomicReference<Order> storedOrder;

    @BeforeEach
    void setUp() {
        clientUser = new AuthenticatedUser(1L, Set.of("CLIENT"), Set.of());
        marginClient = new AuthenticatedUser(1L, Set.of("CLIENT"), Set.of("MARGIN_TRADING"));
        actuaryUser = new AuthenticatedUser(2L, Set.of("AGENT"), Set.of("MARGIN_TRADING"));

        buyRequest = new CreateBuyOrderRequest();
        buyRequest.setListingId(42L);
        buyRequest.setQuantity(10);
        buyRequest.setAccountId(5L);
        buyRequest.setAllOrNone(false);
        buyRequest.setMargin(false);

        sellRequest = new CreateSellOrderRequest();
        sellRequest.setListingId(42L);
        sellRequest.setQuantity(5);
        sellRequest.setAccountId(5L);
        sellRequest.setAllOrNone(false);
        sellRequest.setMargin(false);

        listing = new StockListingDto();
        listing.setId(42L);
        listing.setPrice(new BigDecimal("100.00"));
        listing.setAsk(new BigDecimal("101.00"));
        listing.setBid(new BigDecimal("99.00"));
        listing.setContractSize(1);
        listing.setExchangeId(7L);
        listing.setCurrency("USD");
        listing.setListingType(ListingType.STOCK);
        listing.setVolume(500L);

        exchangeStatus = new ExchangeStatusDto();
        exchangeStatus.setOpen(true);
        exchangeStatus.setAfterHours(false);
        exchangeStatus.setClosed(false);

        accountDetails = new AccountDetailsDto();
        accountDetails.setAccountNumber("ACC-1");
        accountDetails.setBalance(new BigDecimal("50000.00"));
        accountDetails.setCurrency("USD");
        accountDetails.setOwnerId(1L);
        accountDetails.setAvailableCredit(new BigDecimal("1000.00"));

        bankAccount = new BankAccountDto();
        bankAccount.setAccountId(999L);
        employee = new EmployeeDto();
        employee.setId(2L);
        employee.setIme("Bank");
        employee.setPrezime("Account");
        employee.setEmail("bank@example.com");
        storedOrder = new AtomicReference<>();

        ExchangeRateDto usdToRsd = new ExchangeRateDto();
        usdToRsd.setConvertedAmount(new BigDecimal("1170.00"));
        ExchangeRateDto usdCap = new ExchangeRateDto();
        usdCap.setConvertedAmount(new BigDecimal("7.00"));
        ExchangeRateDto limitCap = new ExchangeRateDto();
        limitCap.setConvertedAmount(new BigDecimal("12.00"));

        lenient().when(stockClient.getListing(42L)).thenReturn(listing);
        lenient().when(stockClient.getExchangeStatus(7L)).thenReturn(exchangeStatus);
        lenient().when(accountClient.getAccountDetails(5L)).thenReturn(accountDetails);
        lenient().when(accountClient.getAccountDetails(999L)).thenReturn(accountDetails);
        lenient().doNothing().when(accountClient).transfer(any(AccountTransactionRequest.class));
        lenient().when(employeeClient.getBankAccount("USD")).thenReturn(bankAccount);
        lenient().when(employeeClient.getEmployee(2L)).thenReturn(employee);
        lenient().when(actuaryInfoRepository.findByEmployeeId(1L)).thenReturn(Optional.empty());
        lenient().when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.empty());
        lenient().when(exchangeClient.calculate("USD", "RSD", new BigDecimal("1010.00"))).thenReturn(usdToRsd);
        lenient().when(exchangeClient.calculate("USD", "USD", new BigDecimal("7"))).thenReturn(usdCap);
        lenient().when(exchangeClient.calculate("USD", "USD", new BigDecimal("12"))).thenReturn(limitCap);
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(100L);
            }
            storedOrder.set(order);
            return order;
        });
        lenient().when(orderRepository.findById(100L)).thenAnswer(invocation -> Optional.ofNullable(storedOrder.get()));
        lenient().when(orderRepository.findByIdForUpdate(100L)).thenAnswer(invocation -> Optional.ofNullable(storedOrder.get()));
    }

    @Test
    void createBuyOrder_createsDraftAwaitingConfirmation() {
        OrderResponse response = service.createBuyOrder(clientUser, buyRequest);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(response.getDirection()).isEqualTo(OrderDirection.BUY);
        verify(accountClient, never()).transfer(any());
        verify(orderExecutionService, never()).executeOrderAsync(any());
    }

    @Test
    void confirmBuyOrder_forClientApprovesTransfersFeeAndStartsExecution() {
        service.createBuyOrder(clientUser, buyRequest);

        OrderResponse response = service.confirmOrder(clientUser, 100L);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(response.getApprovedBy()).isEqualTo(OrderCreationServiceImpl.NO_APPROVAL_REQUIRED);
        verify(accountClient).transfer(any(AccountTransactionRequest.class));
        verify(orderExecutionService).executeOrderAsync(100L);

        ArgumentCaptor<AccountTransactionRequest> captor = ArgumentCaptor.forClass(AccountTransactionRequest.class);
        verify(accountClient).transfer(captor.capture());
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    void confirmBuyOrder_forAgentNeedingApprovalMovesToPending() {
        ActuaryInfo agent = new ActuaryInfo();
        agent.setEmployeeId(2L);
        agent.setNeedApproval(true);
        agent.setLimit(new BigDecimal("2000.00"));
        agent.setUsedLimit(BigDecimal.ZERO);
        when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(agent));

        service.createBuyOrder(actuaryUser, buyRequest);
        OrderResponse response = service.confirmOrder(actuaryUser, 100L);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.getApprovedBy()).isNull();
        verify(accountClient).getAccountDetails(5L);
        verify(employeeClient, never()).getBankAccount("USD");
        verify(accountClient, never()).transfer(any(AccountTransactionRequest.class));
        verify(orderExecutionService, never()).executeOrderAsync(any());
    }

    @Test
    void confirmBuyOrder_forAgentFailsWhenSelectedFundingAccountLacksFunds() {
        accountDetails.setBalance(BigDecimal.ONE);

        service.createBuyOrder(actuaryUser, buyRequest);

        assertThatThrownBy(() -> service.confirmOrder(actuaryUser, 100L))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("Insufficient funds");
        verify(accountClient).getAccountDetails(5L);
        verify(employeeClient, never()).getBankAccount("USD");
        verify(accountClient, never()).transfer(any(AccountTransactionRequest.class));
        verify(orderExecutionService, never()).executeOrderAsync(any());
    }

    @Test
    void approveOrder_updatesApprovedByChargesFeeAndStartsExecution() {
        ActuaryInfo agent = new ActuaryInfo();
        agent.setEmployeeId(2L);
        agent.setNeedApproval(true);
        agent.setLimit(new BigDecimal("2000.00"));
        agent.setUsedLimit(BigDecimal.ZERO);
        when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(agent));

        service.createBuyOrder(actuaryUser, buyRequest);
        service.confirmOrder(actuaryUser, 100L);

        OrderResponse response = service.approveOrder(88L, 100L);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(response.getApprovedBy()).isEqualTo(88L);
        verify(accountClient).transfer(any(AccountTransactionRequest.class));
        verify(orderExecutionService).executeOrderAsync(100L);
    }

    @Test
    void approveOrder_rejectsExpiredPendingOrderWithBusinessConflict() {
        ActuaryInfo agent = new ActuaryInfo();
        agent.setEmployeeId(2L);
        agent.setNeedApproval(true);
        agent.setLimit(new BigDecimal("2000.00"));
        agent.setUsedLimit(BigDecimal.ZERO);
        when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(agent));

        service.createBuyOrder(actuaryUser, buyRequest);
        service.confirmOrder(actuaryUser, 100L);
        listing.setSettlementDate(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> service.approveOrder(88L, 100L))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("past settlement date can only be declined");
        assertThat(storedOrder.get().getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(orderExecutionService, never()).executeOrderAsync(any());
    }

    @Test
    void declineOrder_marksPendingAgentOrderDeclinedWithoutChargingFee() {
        ActuaryInfo agent = new ActuaryInfo();
        agent.setEmployeeId(2L);
        agent.setNeedApproval(true);
        agent.setLimit(new BigDecimal("2000.00"));
        agent.setUsedLimit(BigDecimal.ZERO);
        when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(agent));

        service.createBuyOrder(actuaryUser, buyRequest);
        service.confirmOrder(actuaryUser, 100L);

        OrderResponse response = service.declineOrder(77L, 100L);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.DECLINED);
        assertThat(response.getApprovedBy()).isEqualTo(77L);
        verify(accountClient, never()).transfer(any(AccountTransactionRequest.class));
    }

    @Test
    void approveOrder_chargesFeeExactlyOnceAfterPendingConfirmation() {
        ActuaryInfo agent = new ActuaryInfo();
        agent.setEmployeeId(2L);
        agent.setNeedApproval(true);
        agent.setLimit(new BigDecimal("2000.00"));
        agent.setUsedLimit(BigDecimal.ZERO);
        when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(agent));

        service.createBuyOrder(actuaryUser, buyRequest);
        service.confirmOrder(actuaryUser, 100L);
        service.approveOrder(88L, 100L);

        verify(accountClient, times(1)).transfer(any(AccountTransactionRequest.class));
    }

    @Test
    void approveOrder_publishesApprovedNotification() {
        ActuaryInfo agent = new ActuaryInfo();
        agent.setEmployeeId(2L);
        agent.setNeedApproval(true);
        agent.setLimit(new BigDecimal("2000.00"));
        agent.setUsedLimit(BigDecimal.ZERO);
        when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(agent));
        EmployeeDto employee = new EmployeeDto();
        employee.setId(2L);
        employee.setIme("Ana");
        employee.setPrezime("Agent");
        employee.setEmail("ana.agent@example.com");
        when(employeeClient.getEmployee(2L)).thenReturn(employee);

        service.createBuyOrder(actuaryUser, buyRequest);
        service.confirmOrder(actuaryUser, 100L);
        service.approveOrder(88L, 100L);

        ArgumentCaptor<OrderNotificationPayload> payloadCaptor = ArgumentCaptor.forClass(OrderNotificationPayload.class);
        verify(orderNotificationProducer).sendOrderApproved(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().getStatus()).isEqualTo(OrderStatus.APPROVED);
        assertThat(payloadCaptor.getValue().getSupervisorId()).isEqualTo(88L);
        assertThat(payloadCaptor.getValue().getUserEmail()).isEqualTo("ana.agent@example.com");
    }

    @Test
    void declineOrder_publishesDeclinedNotification() {
        ActuaryInfo agent = new ActuaryInfo();
        agent.setEmployeeId(2L);
        agent.setNeedApproval(true);
        agent.setLimit(new BigDecimal("2000.00"));
        agent.setUsedLimit(BigDecimal.ZERO);
        when(actuaryInfoRepository.findByEmployeeId(2L)).thenReturn(Optional.of(agent));
        EmployeeDto employee = new EmployeeDto();
        employee.setId(2L);
        employee.setIme("Ana");
        employee.setPrezime("Agent");
        employee.setEmail("ana.agent@example.com");
        when(employeeClient.getEmployee(2L)).thenReturn(employee);

        service.createBuyOrder(actuaryUser, buyRequest);
        service.confirmOrder(actuaryUser, 100L);
        service.declineOrder(77L, 100L);

        ArgumentCaptor<OrderNotificationPayload> payloadCaptor = ArgumentCaptor.forClass(OrderNotificationPayload.class);
        verify(orderNotificationProducer).sendOrderDeclined(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().getStatus()).isEqualTo(OrderStatus.DECLINED);
        assertThat(payloadCaptor.getValue().getSupervisorId()).isEqualTo(77L);
        assertThat(payloadCaptor.getValue().getUserEmail()).isEqualTo("ana.agent@example.com");
    }

    @Test
    void confirmBuyOrder_withPastSettlementDateAutoDeclines() {
        listing.setSettlementDate(LocalDate.now().minusDays(1));
        service.createBuyOrder(clientUser, buyRequest);

        OrderResponse response = service.confirmOrder(clientUser, 100L);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.DECLINED);
        assertThat(response.getApprovedBy()).isEqualTo(OrderCreationServiceImpl.SYSTEM_APPROVAL);
        verify(orderExecutionService, never()).executeOrderAsync(any());
    }

    @Test
    void confirmMarginBuy_rejectsWhenUserLacksMarginPermission() {
        buyRequest.setMargin(true);
        service.createBuyOrder(clientUser, buyRequest);

        assertThatThrownBy(() -> service.confirmOrder(clientUser, 100L))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("margin permission");
    }

    @Test
    void confirmMarginBuy_acceptsWhenApprovedCreditCoversInitialMargin() {
        buyRequest.setMargin(true);
        accountDetails.setBalance(BigDecimal.ZERO);
        accountDetails.setAvailableCredit(new BigDecimal("10000.00"));

        service.createBuyOrder(marginClient, buyRequest);
        OrderResponse response = service.confirmOrder(marginClient, 100L);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test
    void createBuyOrder_marksAfterHoursWhenExchangeClosed() {
        exchangeStatus.setClosed(true);
        exchangeStatus.setOpen(false);

        service.createBuyOrder(clientUser, buyRequest);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(captor.capture());
        assertThat(captor.getValue().getAfterHours()).isTrue();
    }

    @Test
    void createSellOrder_requiresOwnedPortfolioAndUsesConfirmationFlow() {
        Portfolio portfolio = new Portfolio();
        portfolio.setUserId(1L);
        portfolio.setListingId(42L);
        portfolio.setQuantity(20);
        portfolio.setAveragePurchasePrice(new BigDecimal("90.00"));
        when(portfolioRepository.findByUserIdAndListingId(1L, 42L)).thenReturn(Optional.of(portfolio));

        OrderResponse created = service.createSellOrder(clientUser, sellRequest);
        OrderResponse confirmed = service.confirmOrder(clientUser, 100L);

        assertThat(created.getStatus()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test
    void createSellOrder_rejectsMissingPortfolio() {
        when(portfolioRepository.findByUserIdAndListingId(1L, 42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createSellOrder(clientUser, sellRequest))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Portfolio position not found");
    }

    @Test
    void confirmMarginSell_rejectsWhenUserLacksMarginPermission() {
        Portfolio portfolio = new Portfolio();
        portfolio.setUserId(1L);
        portfolio.setListingId(42L);
        portfolio.setQuantity(20);
        portfolio.setAveragePurchasePrice(new BigDecimal("90.00"));
        when(portfolioRepository.findByUserIdAndListingId(1L, 42L)).thenReturn(Optional.of(portfolio));
        sellRequest.setMargin(true);

        service.createSellOrder(clientUser, sellRequest);

        assertThatThrownBy(() -> service.confirmOrder(clientUser, 100L))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("margin permission");
    }

    @Test
    void confirmMarginSell_acceptsWhenApprovedCreditCoversInitialMargin() {
        Portfolio portfolio = new Portfolio();
        portfolio.setUserId(1L);
        portfolio.setListingId(42L);
        portfolio.setQuantity(20);
        portfolio.setAveragePurchasePrice(new BigDecimal("90.00"));
        when(portfolioRepository.findByUserIdAndListingId(1L, 42L)).thenReturn(Optional.of(portfolio));
        sellRequest.setMargin(true);
        accountDetails.setBalance(BigDecimal.ZERO);
        accountDetails.setAvailableCredit(new BigDecimal("10000.00"));

        service.createSellOrder(marginClient, sellRequest);
        OrderResponse response = service.confirmOrder(marginClient, 100L);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test
    void confirmMarginSell_acceptsWhenFundsCoverInitialMargin() {
        Portfolio portfolio = new Portfolio();
        portfolio.setUserId(1L);
        portfolio.setListingId(42L);
        portfolio.setQuantity(20);
        portfolio.setAveragePurchasePrice(new BigDecimal("90.00"));
        when(portfolioRepository.findByUserIdAndListingId(1L, 42L)).thenReturn(Optional.of(portfolio));
        sellRequest.setMargin(true);
        accountDetails.setAvailableCredit(BigDecimal.ZERO);
        accountDetails.setBalance(new BigDecimal("10000.00"));

        service.createSellOrder(marginClient, sellRequest);
        OrderResponse response = service.confirmOrder(marginClient, 100L);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test
    void confirmMarginSell_rejectsWhenNeitherCreditNorFundsAreSufficient() {
        Portfolio portfolio = new Portfolio();
        portfolio.setUserId(1L);
        portfolio.setListingId(42L);
        portfolio.setQuantity(20);
        portfolio.setAveragePurchasePrice(new BigDecimal("90.00"));
        when(portfolioRepository.findByUserIdAndListingId(1L, 42L)).thenReturn(Optional.of(portfolio));
        sellRequest.setMargin(true);
        accountDetails.setAvailableCredit(BigDecimal.ZERO);
        accountDetails.setBalance(BigDecimal.ONE);

        service.createSellOrder(marginClient, sellRequest);

        assertThatThrownBy(() -> service.confirmOrder(marginClient, 100L))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("Margin requirements");
    }

    @Test
    void getOrders_allReturnsOverviewRowsAndEnrichesActuaryNames() {
        Order actuaryOrder = new Order();
        actuaryOrder.setId(10L);
        actuaryOrder.setUserId(2L);
        actuaryOrder.setListingId(42L);
        actuaryOrder.setOrderType(OrderType.MARKET);
        actuaryOrder.setQuantity(10);
        actuaryOrder.setContractSize(1);
        actuaryOrder.setPricePerUnit(new BigDecimal("101.00"));
        actuaryOrder.setDirection(OrderDirection.BUY);
        actuaryOrder.setRemainingPortions(6);
        actuaryOrder.setStatus(OrderStatus.PENDING);

        Order clientOrder = new Order();
        clientOrder.setId(11L);
        clientOrder.setUserId(1L);
        clientOrder.setListingId(42L);
        clientOrder.setOrderType(OrderType.LIMIT);
        clientOrder.setQuantity(5);
        clientOrder.setContractSize(1);
        clientOrder.setPricePerUnit(new BigDecimal("99.00"));
        clientOrder.setDirection(OrderDirection.SELL);
        clientOrder.setRemainingPortions(0);
        clientOrder.setStatus(OrderStatus.DONE);

        when(orderRepository.findAll()).thenReturn(List.of(actuaryOrder, clientOrder));
        ActuaryInfo actuaryInfo = new ActuaryInfo();
        actuaryInfo.setEmployeeId(2L);
        when(actuaryInfoRepository.findByEmployeeIdIn(java.util.Set.of(1L, 2L))).thenReturn(List.of(actuaryInfo));
        EmployeeDto employee = new EmployeeDto();
        employee.setId(2L);
        employee.setIme("Ana");
        employee.setPrezime("Agent");
        employee.setUsername("aagent");
        when(employeeClient.getEmployee(2L)).thenReturn(employee);

        List<OrderOverviewResponse> response = service.getOrders(OrderOverviewStatusFilter.ALL);

        assertThat(response).hasSize(2);
        assertThat(response.get(0).getOrderId()).isEqualTo(10L);
        assertThat(response.get(0).getAgentName()).isEqualTo("Ana Agent");
        assertThat(response.get(0).getListingType()).isEqualTo(ListingType.STOCK);
        assertThat(response.get(0).getRemainingPortions()).isEqualTo(6);
        assertThat(response.get(1).getAgentName()).isNull();
        verify(employeeClient).getEmployee(2L);
        verify(stockClient, times(1)).getListing(42L);
    }

    @Test
    void getOrders_filtersByRequestedStatus() {
        Order approvedOrder = new Order();
        approvedOrder.setId(12L);
        approvedOrder.setUserId(1L);
        approvedOrder.setListingId(42L);
        approvedOrder.setOrderType(OrderType.MARKET);
        approvedOrder.setQuantity(4);
        approvedOrder.setContractSize(1);
        approvedOrder.setPricePerUnit(new BigDecimal("100.00"));
        approvedOrder.setDirection(OrderDirection.BUY);
        approvedOrder.setRemainingPortions(4);
        approvedOrder.setStatus(OrderStatus.APPROVED);
        when(orderRepository.findByStatus(OrderStatus.APPROVED)).thenReturn(List.of(approvedOrder));
        when(actuaryInfoRepository.findByEmployeeIdIn(java.util.Set.of(1L))).thenReturn(List.of());

        List<OrderOverviewResponse> response = service.getOrders(OrderOverviewStatusFilter.APPROVED);

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().getStatus()).isEqualTo(OrderStatus.APPROVED);
    }

    @Test
    void getOrders_deduplicatesListingAndEmployeeLookupsWithinRequest() {
        Order first = new Order();
        first.setId(301L);
        first.setUserId(2L);
        first.setListingId(42L);
        first.setOrderType(OrderType.MARKET);
        first.setQuantity(1);
        first.setContractSize(1);
        first.setPricePerUnit(new BigDecimal("100.00"));
        first.setDirection(OrderDirection.BUY);
        first.setRemainingPortions(1);
        first.setStatus(OrderStatus.PENDING);

        Order second = new Order();
        second.setId(302L);
        second.setUserId(2L);
        second.setListingId(42L);
        second.setOrderType(OrderType.LIMIT);
        second.setQuantity(2);
        second.setContractSize(1);
        second.setPricePerUnit(new BigDecimal("101.00"));
        second.setDirection(OrderDirection.SELL);
        second.setRemainingPortions(2);
        second.setStatus(OrderStatus.APPROVED);

        ActuaryInfo actuaryInfo = new ActuaryInfo();
        actuaryInfo.setEmployeeId(2L);
        EmployeeDto employee = new EmployeeDto();
        employee.setId(2L);
        employee.setIme("Ana");
        employee.setPrezime("Agent");
        employee.setUsername("aagent");

        when(orderRepository.findAll()).thenReturn(List.of(first, second));
        when(actuaryInfoRepository.findByEmployeeIdIn(java.util.Set.of(2L))).thenReturn(List.of(actuaryInfo));
        when(employeeClient.getEmployee(2L)).thenReturn(employee);

        List<OrderOverviewResponse> response = service.getOrders(OrderOverviewStatusFilter.ALL);

        assertThat(response).hasSize(2);
        verify(stockClient, times(1)).getListing(42L);
        verify(employeeClient, times(1)).getEmployee(2L);
    }

    @Test
    void supervisorCancel_preservesRemainingPortionsAndStopsExecution() {
        Order order = new Order();
        order.setId(200L);
        order.setUserId(2L);
        order.setListingId(42L);
        order.setOrderType(OrderType.MARKET);
        order.setQuantity(10);
        order.setContractSize(1);
        order.setPricePerUnit(new BigDecimal("101.00"));
        order.setDirection(OrderDirection.BUY);
        order.setRemainingPortions(4);
        order.setStatus(OrderStatus.APPROVED);
        order.setIsDone(false);
        order.setAccountId(5L);
        when(orderRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(order));

        OrderResponse response = service.cancelOrder(200L);

        assertThat(response.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.getRemainingPortions()).isEqualTo(4);
        assertThat(response.getIsDone()).isTrue();
    }

    @Test
    void supervisorCancel_rejectsTerminalOrders() {
        Order order = new Order();
        order.setId(201L);
        order.setUserId(2L);
        order.setListingId(42L);
        order.setStatus(OrderStatus.DONE);
        order.setIsDone(true);
        when(orderRepository.findByIdForUpdate(201L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(201L))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("no longer be cancelled");
    }

    @Test
    void autoDeclineExpiredPendingOrders_declinesOnlyExpiredPendingOrders() {
        Order expiredPending = new Order();
        expiredPending.setId(201L);
        expiredPending.setUserId(2L);
        expiredPending.setListingId(42L);
        expiredPending.setOrderType(OrderType.MARKET);
        expiredPending.setDirection(OrderDirection.BUY);
        expiredPending.setQuantity(10);
        expiredPending.setContractSize(1);
        expiredPending.setPricePerUnit(new BigDecimal("101.00"));
        expiredPending.setRemainingPortions(10);
        expiredPending.setStatus(OrderStatus.PENDING);
        expiredPending.setAccountId(5L);

        Order activePending = new Order();
        activePending.setId(202L);
        activePending.setUserId(2L);
        activePending.setListingId(43L);
        activePending.setOrderType(OrderType.MARKET);
        activePending.setDirection(OrderDirection.BUY);
        activePending.setQuantity(10);
        activePending.setContractSize(1);
        activePending.setPricePerUnit(new BigDecimal("101.00"));
        activePending.setRemainingPortions(10);
        activePending.setStatus(OrderStatus.PENDING);
        activePending.setAccountId(5L);

        Order approvedOrder = new Order();
        approvedOrder.setId(203L);
        approvedOrder.setUserId(2L);
        approvedOrder.setListingId(44L);
        approvedOrder.setStatus(OrderStatus.APPROVED);

        StockListingDto expiredListing = new StockListingDto();
        expiredListing.setId(42L);
        expiredListing.setSettlementDate(LocalDate.now().minusDays(1));

        StockListingDto activeListing = new StockListingDto();
        activeListing.setId(43L);
        activeListing.setSettlementDate(LocalDate.now().plusDays(1));

        EmployeeDto employee = new EmployeeDto();
        employee.setId(2L);
        employee.setIme("Ana");
        employee.setPrezime("Agent");
        employee.setEmail("ana.agent@example.com");

        when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(expiredPending, activePending));
        when(orderRepository.findByIdForUpdate(201L)).thenReturn(Optional.of(expiredPending));
        when(orderRepository.findByIdForUpdate(202L)).thenReturn(Optional.of(activePending));
        when(stockClient.getListing(42L)).thenReturn(expiredListing);
        when(stockClient.getListing(43L)).thenReturn(activeListing);
        when(employeeClient.getEmployee(2L)).thenReturn(employee);

        service.autoDeclineExpiredPendingOrders();

        assertThat(expiredPending.getStatus()).isEqualTo(OrderStatus.DECLINED);
        assertThat(expiredPending.getApprovedBy()).isEqualTo(OrderCreationServiceImpl.SYSTEM_APPROVAL);
        assertThat(activePending.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(orderRepository).save(expiredPending);
        verify(orderRepository, never()).save(activePending);
        verify(orderNotificationProducer).sendOrderDeclined(any(OrderNotificationPayload.class));
    }
}
