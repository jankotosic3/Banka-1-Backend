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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExecutionServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private PortfolioRepository portfolioRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private StockClient stockClient;
    @Mock
    private AccountClient accountClient;
    @Mock
    private EmployeeClient employeeClient;
    @Mock
    private ExchangeClient exchangeClient;
    @Mock
    private ActuaryInfoRepository actuaryInfoRepository;

    @InjectMocks
    private OrderExecutionServiceImpl service;

    private Order order;
    private StockListingDto listing;
    private EmployeeDto bankAccount;
    private Portfolio portfolio;
    private ActuaryInfo actuaryInfo;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(10L);
        order.setUserId(1L);
        order.setListingId(42L);
        order.setOrderType(OrderType.MARKET);
        order.setDirection(OrderDirection.BUY);
        order.setStatus(OrderStatus.APPROVED);
        order.setQuantity(1);
        order.setContractSize(2);
        order.setRemainingPortions(1);
        order.setIsDone(false);
        order.setAfterHours(false);
        order.setAllOrNone(false);
        order.setAccountId(5L);

        listing = new StockListingDto();
        listing.setId(42L);
        listing.setPrice(new BigDecimal("100.00"));
        listing.setAsk(new BigDecimal("101.00"));
        listing.setBid(new BigDecimal("99.00"));
        listing.setContractSize(2);
        listing.setCurrency("USD");
        listing.setVolume(50L);
        listing.setListingType(ListingType.STOCK);

        bankAccount = new EmployeeDto();
        bankAccount.setId(999L);

        portfolio = new Portfolio();
        portfolio.setId(50L);
        portfolio.setUserId(1L);
        portfolio.setListingId(42L);
        portfolio.setListingType(ListingType.STOCK);
        portfolio.setQuantity(5);
        portfolio.setAveragePurchasePrice(new BigDecimal("95.00"));

        actuaryInfo = new ActuaryInfo();
        actuaryInfo.setEmployeeId(1L);
        actuaryInfo.setUsedLimit(BigDecimal.ZERO);
        actuaryInfo.setLimit(new BigDecimal("100000.00"));

        ExchangeRateDto usdToRsd = new ExchangeRateDto();
        usdToRsd.setConvertedAmount(new BigDecimal("23634.00"));
        ExchangeRateDto usdCap = new ExchangeRateDto();
        usdCap.setConvertedAmount(new BigDecimal("7.00"));
        ExchangeRateDto limitCap = new ExchangeRateDto();
        limitCap.setConvertedAmount(new BigDecimal("12.00"));

        lenient().when(stockClient.getListing(42L)).thenReturn(listing);
        lenient().when(employeeClient.getBankAccount("USD")).thenReturn(bankAccount);
        lenient().when(portfolioRepository.findByUserIdAndListingId(1L, 42L)).thenReturn(Optional.of(portfolio));
        lenient().when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(portfolioRepository.save(any(Portfolio.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().doNothing().when(accountClient).transfer(any(AccountTransactionRequest.class));
        lenient().when(exchangeClient.calculate("USD", "RSD", new BigDecimal("202.00"))).thenReturn(usdToRsd);
        lenient().when(exchangeClient.calculate("USD", "USD", new BigDecimal("7"))).thenReturn(usdCap);
        lenient().when(exchangeClient.calculate("USD", "USD", new BigDecimal("12"))).thenReturn(limitCap);
    }

    @Test
    void marketBuy_executesUsingAskPriceTransfersFundsAndCompletesOrder() {
        when(portfolioRepository.findByUserIdAndListingId(1L, 42L)).thenReturn(Optional.empty());

        service.executeOrderPortion(order);

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        assertThat(transactionCaptor.getValue().getPricePerUnit()).isEqualByComparingTo("101.00");
        assertThat(transactionCaptor.getValue().getTotalPrice()).isEqualByComparingTo("202.00");

        ArgumentCaptor<AccountTransactionRequest> transferCaptor = ArgumentCaptor.forClass(AccountTransactionRequest.class);
        verify(accountClient).transfer(transferCaptor.capture());
        assertThat(transferCaptor.getValue().getFromAccountId()).isEqualTo(5L);
        assertThat(transferCaptor.getValue().getToAccountId()).isEqualTo(999L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DONE);
        assertThat(order.getIsDone()).isTrue();
    }

    @Test
    void limitBuy_executesAtMinimumOfLimitAndAsk() {
        order.setOrderType(OrderType.LIMIT);
        order.setLimitValue(new BigDecimal("105.00"));

        service.executeOrderPortion(order);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getPricePerUnit()).isEqualByComparingTo("101.00");
    }

    @Test
    void limitSell_executesAtMaximumOfLimitAndBid() {
        order.setDirection(OrderDirection.SELL);
        order.setOrderType(OrderType.LIMIT);
        order.setLimitValue(new BigDecimal("95.00"));

        service.executeOrderPortion(order);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getPricePerUnit()).isEqualByComparingTo("99.00");
    }

    @Test
    void stopBuy_activatesThenBehavesAsMarketBuy() {
        order.setOrderType(OrderType.STOP);
        order.setStopValue(new BigDecimal("100.00"));

        service.executeOrderPortion(order);

        assertThat(order.getOrderType()).isEqualTo(OrderType.MARKET);
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void stopLimitSell_activatesThenBehavesAsLimitSell() {
        order.setDirection(OrderDirection.SELL);
        order.setOrderType(OrderType.STOP_LIMIT);
        order.setStopValue(new BigDecimal("100.00"));
        order.setLimitValue(new BigDecimal("95.00"));

        service.executeOrderPortion(order);

        assertThat(order.getOrderType()).isEqualTo(OrderType.LIMIT);
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getPricePerUnit()).isEqualByComparingTo("99.00");
    }

    @Test
    void aonOrder_doesNotExecuteWhenVolumeCannotFillEverything() {
        order.setAllOrNone(true);
        order.setRemainingPortions(10);
        listing.setVolume(5L);

        service.executeOrderPortion(order);

        verify(transactionRepository, never()).save(any());
        assertThat(order.getRemainingPortions()).isEqualTo(10);
    }

    @Test
    void chunkedExecution_usesRandomQuantityWithinRemainingAndVolume() {
        order.setRemainingPortions(5);
        order.setQuantity(5);
        listing.setVolume(3L);

        service.executeOrderPortion(order);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getQuantity()).isBetween(1, 3);
    }

    @Test
    void buyExecution_persistsNewPortfolioWhenPositionDoesNotExist() {
        when(portfolioRepository.findByUserIdAndListingId(1L, 42L)).thenReturn(Optional.empty());

        service.executeOrderPortion(order);

        ArgumentCaptor<Portfolio> captor = ArgumentCaptor.forClass(Portfolio.class);
        verify(portfolioRepository).save(captor.capture());
        assertThat(captor.getValue().getListingType()).isEqualTo(ListingType.STOCK);
        assertThat(captor.getValue().getQuantity()).isEqualTo(1);
    }

    @Test
    void sellExecution_reducesPortfolioAndTransfersProceeds() {
        order.setDirection(OrderDirection.SELL);
        portfolio.setQuantity(3);

        service.executeOrderPortion(order);

        verify(portfolioRepository, atLeastOnce()).save(portfolio);
        ArgumentCaptor<AccountTransactionRequest> captor = ArgumentCaptor.forClass(AccountTransactionRequest.class);
        verify(accountClient).transfer(captor.capture());
        assertThat(captor.getValue().getFromAccountId()).isEqualTo(999L);
        assertThat(captor.getValue().getToAccountId()).isEqualTo(5L);
    }

    @Test
    void actuaryExecution_updatesUsedLimitWithCurrencyConversion() {
        when(actuaryInfoRepository.findByEmployeeId(1L)).thenReturn(Optional.of(actuaryInfo));

        service.executeOrderPortion(order);

        verify(actuaryInfoRepository).save(actuaryInfo);
        assertThat(actuaryInfo.getUsedLimit()).isEqualByComparingTo("23634.00");
    }

    @Test
    void actuaryBuy_usesDistinctTransferAccounts() {
        when(actuaryInfoRepository.findByEmployeeId(1L)).thenReturn(Optional.of(actuaryInfo));

        service.executeOrderPortion(order);

        ArgumentCaptor<AccountTransactionRequest> captor = ArgumentCaptor.forClass(AccountTransactionRequest.class);
        verify(accountClient).transfer(captor.capture());
        assertThat(captor.getValue().getFromAccountId()).isEqualTo(999L);
        assertThat(captor.getValue().getToAccountId()).isEqualTo(5L);
        assertThat(captor.getValue().getFromAccountId()).isNotEqualTo(captor.getValue().getToAccountId());
    }

    @Test
    void afterHoursDelayAddsThirtyMinutes() {
        order.setAfterHours(true);

        long delay = (long) ReflectionTestUtils.invokeMethod(service, "calculateExecutionDelay", order);

        assertThat(delay).isGreaterThanOrEqualTo(30L * 60L * 1000L);
    }
}
