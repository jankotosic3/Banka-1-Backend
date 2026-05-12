package com.banka1.bankingcore.transaction.service.margin;

import com.banka1.bankingcore.account.client.AccountServiceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Posle PR_14 C14.4 BankToExchangeTransferService ne drzi vise lokalne entitete —
 * sve ide kroz {@link AccountServiceClient}. Stari test (sa AccountRepository
 * mock-ovima) je obrisan jer su entiteti banking_core.Account / banking_core.Transaction
 * uklonjeni (banking_core DB ih nije ni imala).
 */
@ExtendWith(MockitoExtension.class)
class BankToExchangeTransferServiceTest {

    @Mock private ObjectProvider<AccountServiceClient> accountClientProvider;
    @Mock private AccountServiceClient accountClient;

    @InjectMocks private BankToExchangeTransferService service;

    private void primeFields() {
        ReflectionTestUtils.setField(service, "bankAccountNumber", "1234567812345670");
        ReflectionTestUtils.setField(service, "exchangeAccountNumber", "9876543212345674");
        ReflectionTestUtils.setField(service, "bankClientId", -1L);
        ReflectionTestUtils.setField(service, "exchangeClientId", -3L);
    }

    @Test
    void sendToExchange_pozivaDebitNaBankAccountIKreditNaExchange() {
        primeFields();
        when(accountClientProvider.getIfAvailable()).thenReturn(accountClient);

        Long txId = service.sendToExchange(new BigDecimal("3000.00"), "corr-1");

        verify(accountClient).debit("1234567812345670", new BigDecimal("3000.00"), -1L);
        verify(accountClient).credit("9876543212345674", new BigDecimal("3000.00"), -3L);
        org.assertj.core.api.Assertions.assertThat(txId).isPositive();
    }

    @Test
    void receiveFromExchange_pozivaDebitNaExchangeIKreditNaBankAccount() {
        primeFields();
        when(accountClientProvider.getIfAvailable()).thenReturn(accountClient);

        service.receiveFromExchange(new BigDecimal("2000.00"), "corr-2");

        verify(accountClient).debit("9876543212345674", new BigDecimal("2000.00"), -3L);
        verify(accountClient).credit("1234567812345670", new BigDecimal("2000.00"), -1L);
    }

    @Test
    void sendToExchange_throws_kadKlijentNijeKonfigurisan() {
        primeFields();
        when(accountClientProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.sendToExchange(new BigDecimal("3000"), "corr"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AccountServiceClient nije dostupan");
    }
}
