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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    private EmployeeDto bankAccount;
    private AtomicReference<Order> storedOrder;

    @BeforeEach
    void setUp() {
        clientUser = new AuthenticatedUser(1L, Set.of("CLIENT"), Set.of());
        marginClient = new AuthenticatedUser(1L, Set.of("CLIENT"), Set.of("MARGIN_TRADING"));
        actuaryUser = new AuthenticatedUser(2L, Set.of("ACTUARY"), Set.of("MARGIN_TRADING"));

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

        bankAccount = new EmployeeDto();
        bankAccount.setId(999L);
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
        verify(orderExecutionService, never()).executeOrderAsync(any());
    }

    @Test
    void approveOrder_updatesApprovedByAndStartsExecution() {
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
        verify(orderExecutionService).executeOrderAsync(100L);
    }

    @Test
    void declineOrder_marksPendingAgentOrderDeclined() {
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
                .isInstanceOf(IllegalArgumentException.class)
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
                .isInstanceOf(IllegalArgumentException.class)
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
                .isInstanceOf(IllegalArgumentException.class)
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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Margin requirements");
    }
}
