package com.banka1.transaction_service.service.implementation;

import com.banka1.transaction_service.domain.Payment;
import com.banka1.transaction_service.domain.enums.CurrencyCode;
import com.banka1.transaction_service.domain.enums.TransactionStatus;
import com.banka1.transaction_service.dto.request.NewPaymentDto;
import com.banka1.transaction_service.dto.response.AccountDetailsResponseDto;
import com.banka1.transaction_service.dto.response.ConversionResponseDto;
import com.banka1.transaction_service.dto.response.InfoResponseDto;
import com.banka1.transaction_service.dto.response.NewPaymentResponseDto;
import com.banka1.transaction_service.dto.response.TransactionResponseDto;
import com.banka1.transaction_service.dto.response.VerificationStatusResponse;
import com.banka1.transaction_service.exception.BusinessException;
import com.banka1.transaction_service.repository.PaymentRepository;
import com.banka1.transaction_service.rest_client.AccountService;
import com.banka1.transaction_service.rest_client.ExchangeService;
import com.banka1.transaction_service.rest_client.VerificationService;
import com.banka1.transaction_service.service.TransactionServiceInternal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceImplementationTest {

    @Mock
    private ExchangeService exchangeService;

    @Mock
    private VerificationService verificationService;

    @Mock
    private AccountService accountService;

    @Mock
    private com.banka1.transaction_service.rest_client.ClientService clientService;

    @Mock
    private TransactionServiceInternal transactionServiceInternal;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private Jwt jwt;

    @InjectMocks
    private TransactionServiceImplementation service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "appPropertiesId", "id");
        ReflectionTestUtils.setField(service, "roles", "roles");
        ReflectionTestUtils.setField(service, "skipVerification", true);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    // ─────────────────────── newPayment ───────────────────────

    @Test
    void newPaymentSucceedsWithSameOwnerTransfer() {
        NewPaymentDto dto = validPaymentDto();
        InfoResponseDto info = infoDto(1L, 1L); // same owner
        ConversionResponseDto conversion = conversionDto();

        when(jwt.getClaim("id")).thenReturn(1L);
        when(accountService.getInfo(dto.getFromAccountNumber(), dto.getToAccountNumber())).thenReturn(info);
        when(exchangeService.calculate(info.getFromCurrencyCode(), info.getToCurrencyCode(), dto.getAmount()))
                .thenReturn(conversion);
        when(transactionServiceInternal.create(eq(jwt), eq(dto), eq(info), eq(conversion))).thenReturn(10L);
        when(accountService.transfer(any())).thenReturn(null);

        NewPaymentResponseDto response = service.newPayment(jwt, dto);

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        verify(accountService).transfer(any());
        verify(accountService, never()).transaction(any());
    }

    @Test
    void newPaymentSucceedsWithDifferentOwnerTransaction() {
        NewPaymentDto dto = validPaymentDto();
        InfoResponseDto info = infoDto(1L, 2L); // different owners
        ConversionResponseDto conversion = conversionDto();

        when(jwt.getClaim("id")).thenReturn(1L);
        when(accountService.getInfo(dto.getFromAccountNumber(), dto.getToAccountNumber())).thenReturn(info);
        when(exchangeService.calculate(info.getFromCurrencyCode(), info.getToCurrencyCode(), dto.getAmount()))
                .thenReturn(conversion);
        when(transactionServiceInternal.create(eq(jwt), eq(dto), eq(info), eq(conversion))).thenReturn(10L);
        when(accountService.transaction(any())).thenReturn(null);

        NewPaymentResponseDto response = service.newPayment(jwt, dto);

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        verify(accountService).transaction(any());
        verify(accountService, never()).transfer(any());
    }

    @Test
    void newPaymentReturnsDeniedWhenTransferAlwaysFails() {
        NewPaymentDto dto = validPaymentDto();
        InfoResponseDto info = infoDto(1L, 1L);
        ConversionResponseDto conversion = conversionDto();

        when(jwt.getClaim("id")).thenReturn(1L);
        when(accountService.getInfo(dto.getFromAccountNumber(), dto.getToAccountNumber())).thenReturn(info);
        when(exchangeService.calculate(info.getFromCurrencyCode(), info.getToCurrencyCode(), dto.getAmount()))
                .thenReturn(conversion);
        when(transactionServiceInternal.create(eq(jwt), eq(dto), eq(info), eq(conversion))).thenReturn(10L);
        when(accountService.transfer(any())).thenThrow(new org.springframework.web.client.RestClientException("err"));

        NewPaymentResponseDto response = service.newPayment(jwt, dto);

        assertThat(response.getStatus()).isEqualTo("DENIED");
    }

    @Test
    void newPaymentThrowsWhenAccountInfoIsNull() {
        NewPaymentDto dto = validPaymentDto();

        when(accountService.getInfo(anyString(), anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.newPayment(jwt, dto))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void newPaymentThrowsWhenConversionIsNull() {
        NewPaymentDto dto = validPaymentDto();
        InfoResponseDto info = infoDto(1L, 2L);

        when(accountService.getInfo(dto.getFromAccountNumber(), dto.getToAccountNumber())).thenReturn(info);
        when(exchangeService.calculate(any(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> service.newPayment(jwt, dto))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void newPaymentThrowsNoSuchElementWhenAccountNotFound() {
        NewPaymentDto dto = validPaymentDto();

        when(accountService.getInfo(anyString(), anyString()))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Not Found"));

        assertThatThrownBy(() -> service.newPayment(jwt, dto))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void newPaymentChecksVerificationWhenNotSkipped() {
        ReflectionTestUtils.setField(service, "skipVerification", false);
        NewPaymentDto dto = validPaymentDto();
        VerificationStatusResponse verified = new VerificationStatusResponse();
        verified.setStatus("VERIFIED");

        when(verificationService.getStatus(dto.getVerificationSessionId())).thenReturn(verified);
        when(accountService.getInfo(dto.getFromAccountNumber(), dto.getToAccountNumber()))
                .thenReturn(infoDto(1L, 1L));
        when(exchangeService.calculate(any(), any(), any())).thenReturn(conversionDto());
        when(transactionServiceInternal.create(any(), any(), any(), any())).thenReturn(10L);
        when(jwt.getClaim("id")).thenReturn(1L);
        when(accountService.transfer(any())).thenReturn(null);

        NewPaymentResponseDto response = service.newPayment(jwt, dto);

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        verify(verificationService).getStatus(dto.getVerificationSessionId());
    }

    @Test
    void newPaymentThrowsWhenVerificationFails() {
        ReflectionTestUtils.setField(service, "skipVerification", false);
        NewPaymentDto dto = validPaymentDto();
        VerificationStatusResponse notVerified = new VerificationStatusResponse();
        notVerified.setStatus("PENDING");

        when(verificationService.getStatus(dto.getVerificationSessionId())).thenReturn(notVerified);

        assertThatThrownBy(() -> service.newPayment(jwt, dto))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void newPaymentThrowsWhenVerificationResponseIsNull() {
        ReflectionTestUtils.setField(service, "skipVerification", false);
        NewPaymentDto dto = validPaymentDto();

        when(verificationService.getStatus(dto.getVerificationSessionId())).thenReturn(null);

        assertThatThrownBy(() -> service.newPayment(jwt, dto))
                .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────── findAllTransactions ───────────────────────

    @Test
    void findAllTransactionsReturnsPagesForEmployee() {
        Payment payment = new Payment();
        when(jwt.getClaimAsString("roles")).thenReturn("BASIC");
        when(paymentRepository.findByAccountNumber(eq("1110001000000000011"), any()))
                .thenReturn(new PageImpl<>(List.of(payment)));

        Page<TransactionResponseDto> result = service.findAllTransactions(jwt, "1110001000000000011", 0, 10);

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findAllTransactionsThrowsForClientNotOwner() {
        AccountDetailsResponseDto details = new AccountDetailsResponseDto();
        details.setVlasnik(99L);

        when(jwt.getClaimAsString("roles")).thenReturn("CLIENT_BASIC");
        when(jwt.getClaim("id")).thenReturn(1L);
        when(accountService.getDetails("1110001000000000011")).thenReturn(details);

        assertThatThrownBy(() -> service.findAllTransactions(jwt, "1110001000000000011", 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vlasnik");
    }

    @Test
    void findAllTransactionsSucceedsForAccountOwner() {
        AccountDetailsResponseDto details = new AccountDetailsResponseDto();
        details.setVlasnik(1L);

        when(jwt.getClaimAsString("roles")).thenReturn("CLIENT_BASIC");
        when(jwt.getClaim("id")).thenReturn(1L);
        when(accountService.getDetails("1110001000000000011")).thenReturn(details);
        when(paymentRepository.findByAccountNumber(eq("1110001000000000011"), any()))
                .thenReturn(new PageImpl<>(List.of(new Payment())));

        Page<TransactionResponseDto> result = service.findAllTransactions(jwt, "1110001000000000011", 0, 10);

        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findAllTransactionsThrowsWhenDetailsNull() {
        when(jwt.getClaimAsString("roles")).thenReturn("CLIENT_BASIC");
        when(accountService.getDetails("1110001000000000011")).thenReturn(null);

        assertThatThrownBy(() -> service.findAllTransactions(jwt, "1110001000000000011", 0, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sistemska greska");
    }

    // ─────────────────────── findPayments ───────────────────────

    @Test
    void findPaymentsReturnsResultsForEmployee() {
        when(jwt.getClaimAsString("roles")).thenReturn("BASIC");
        when(paymentRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<TransactionResponseDto> result = service.findPayments(
                jwt, null, null, null, null, null, null, null, null, 0, 10);

        assertThat(result).isNotNull();
    }

    @Test
    void findPaymentsFiltersForClientBasicOwner() {
        AccountDetailsResponseDto details = new AccountDetailsResponseDto();
        details.setVlasnik(1L);

        when(jwt.getClaimAsString("roles")).thenReturn("CLIENT_BASIC");
        when(jwt.getClaim("id")).thenReturn(1L);
        when(accountService.getDetails("1110001000000000011")).thenReturn(details);
        when(paymentRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<TransactionResponseDto> result = service.findPayments(
                jwt, "1110001000000000011", null, null, null, null, null, null, null, 0, 10);

        assertThat(result).isNotNull();
    }

    @Test
    void findPaymentsThrowsForClientBasicNotOwner() {
        AccountDetailsResponseDto details = new AccountDetailsResponseDto();
        details.setVlasnik(99L);

        when(jwt.getClaimAsString("roles")).thenReturn("CLIENT_BASIC");
        when(jwt.getClaim("id")).thenReturn(1L);
        when(accountService.getDetails("1110001000000000011")).thenReturn(details);

        assertThatThrownBy(() -> service.findPayments(
                jwt, "1110001000000000011", null, null, null, null, null, null, null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─────────────────────── findAllTransactionsForEmployee ───────────────────────

    @Test
    void findAllTransactionsForEmployeeReturnsPaginatedResults() {
        Payment payment = new Payment();
        when(paymentRepository.findByAccountNumber(eq("1110001000000000011"), any()))
                .thenReturn(new PageImpl<>(List.of(payment)));

        Page<TransactionResponseDto> result = service.findAllTransactionsForEmployee("1110001000000000011", 0, 10);

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void findAllTransactionsForEmployeeReturnsEmptyPageWhenNoTransactions() {
        when(paymentRepository.findByAccountNumber(anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        Page<TransactionResponseDto> result = service.findAllTransactionsForEmployee("9990001000000000011", 0, 10);

        assertThat(result.getTotalElements()).isEqualTo(0);
    }

    // ─────────────────────── helpers ───────────────────────

    private NewPaymentDto validPaymentDto() {
        NewPaymentDto dto = new NewPaymentDto();
        dto.setFromAccountNumber("1110001000000000011");
        dto.setToAccountNumber("1110001000000000012");
        dto.setAmount(new BigDecimal("100.00"));
        dto.setRecipientName("Pera Peric");
        dto.setPaymentCode("289");
        dto.setPaymentPurpose("Stanarino");
        dto.setVerificationSessionId(1L);
        return dto;
    }

    private InfoResponseDto infoDto(Long fromOwner, Long toOwner) {
        return new InfoResponseDto(CurrencyCode.RSD, CurrencyCode.RSD, fromOwner, toOwner,
                "pera@example.com", "pera");
    }

    private ConversionResponseDto conversionDto() {
        return new ConversionResponseDto("RSD", "RSD", new BigDecimal("100.00"),
                new BigDecimal("100.00"), BigDecimal.ONE, BigDecimal.ZERO, LocalDate.now());
    }
}
