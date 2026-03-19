package com.banka1.account_service.service.implementation;

import com.banka1.account_service.domain.Account;
import com.banka1.account_service.domain.CheckingAccount;
import com.banka1.account_service.domain.Company;
import com.banka1.account_service.domain.Currency;
import com.banka1.account_service.domain.FxAccount;
import com.banka1.account_service.domain.SifraDelatnosti;
import com.banka1.account_service.domain.enums.AccountConcrete;
import com.banka1.account_service.domain.enums.AccountOwnershipType;
import com.banka1.account_service.domain.enums.CurrencyCode;
import com.banka1.account_service.domain.enums.Status;
import com.banka1.account_service.dto.request.CheckingDto;
import com.banka1.account_service.dto.request.FirmaDto;
import com.banka1.account_service.dto.request.FxDto;
import com.banka1.account_service.dto.response.ClientIdResponseDto;
import com.banka1.account_service.dto.response.ClientResponseDto;
import com.banka1.account_service.dto.response.Pol;
import com.banka1.account_service.repository.AccountRepository;
import com.banka1.account_service.repository.CompanyRepository;
import com.banka1.account_service.repository.CurrencyRepository;
import com.banka1.account_service.repository.SifraDelatnostiRepository;
import com.banka1.account_service.rest_client.ClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeServiceImplementationTest {

    @Mock
    private ClientService clientService;
    @Mock
    private CurrencyRepository currencyRepository;
    @Mock
    private SifraDelatnostiRepository sifraDelatnostiRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private AccountRepository accountRepository;

    @Captor
    private ArgumentCaptor<Account> accountCaptor;

    private EmployeeServiceImplementation service;

    @BeforeEach
    void setUp() {
        service = new EmployeeServiceImplementation(
                7L,
                clientService,
                currencyRepository,
                sifraDelatnostiRepository,
                companyRepository,
                accountRepository
        );
        ReflectionTestUtils.setField(service, "appPropertiesId", "employeeId");
    }

    @Test
    void createFxAccountSavesAccountAndResolvesOwnerFromJmbg() {
        Currency eur = new Currency("Euro", CurrencyCode.EUR, 'E', Set.of("RS"), "opis", Status.ACTIVE);
        FirmaDto firmaDto = new FirmaDto("Firma", "123", "456", "6201", "Adresa", 77L);
        FxDto fxDto = new FxDto("Devizni", null, "0101990712345", CurrencyCode.EUR, AccountOwnershipType.BUSINESS, firmaDto);
        SifraDelatnosti sifraDelatnosti = org.mockito.Mockito.mock(SifraDelatnosti.class);

        when(currencyRepository.findByOznaka(CurrencyCode.EUR)).thenReturn(Optional.of(eur));
        when(sifraDelatnostiRepository.findByOznaka("6201")).thenReturn(Optional.of(sifraDelatnosti));
        when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(clientService.getUser("0101990712345")).thenReturn(new ClientIdResponseDto(55L));
        when(accountRepository.existsByBrojRacuna(anyString())).thenReturn(false);

        String result = service.createFxAccount(jwtWithEmployeeId(900L), fxDto);

        assertThat(result).isEqualTo("Uspesno kreiran fx account");
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount).isInstanceOf(FxAccount.class);
        assertThat(savedAccount.getVlasnik()).isEqualTo(55L);
        assertThat(savedAccount.getZaposlen()).isEqualTo(900L);
        assertThat(savedAccount.getNazivRacuna()).isEqualTo("Devizni");
        assertThat(savedAccount.getCurrency()).isSameAs(eur);
        assertThat(savedAccount.getCompany()).isNotNull();
        assertThat(savedAccount.getBrojRacuna()).startsWith("1110001");
    }

    @Test
    void createCheckingAccountSavesRsdPersonalAccount() {
        Currency rsd = new Currency("Dinar", CurrencyCode.RSD, 'R', Set.of("RS"), "opis", Status.ACTIVE);
        CheckingDto checkingDto = new CheckingDto("Tekuci", 42L, null, AccountConcrete.STANDARDNI, null);

        when(currencyRepository.findByOznaka(CurrencyCode.RSD)).thenReturn(Optional.of(rsd));
        when(accountRepository.existsByBrojRacuna(anyString())).thenReturn(false);

        String result = service.createCheckingAccount(jwtWithEmployeeId(901L), checkingDto);

        assertThat(result).isEqualTo("Uspesno kreiran checking account");
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount).isInstanceOf(CheckingAccount.class);
        assertThat(savedAccount.getVlasnik()).isEqualTo(42L);
        assertThat(savedAccount.getZaposlen()).isEqualTo(901L);
        assertThat(savedAccount.getCurrency()).isSameAs(rsd);
        assertThat(savedAccount.getCompany()).isNull();
    }

    @Test
    void createCheckingAccountRejectsMissingOwner() {
        CheckingDto checkingDto = new CheckingDto("Tekuci", null, " ", AccountConcrete.STANDARDNI, null);

        assertThatThrownBy(() -> service.createCheckingAccount(jwtWithEmployeeId(901L), checkingDto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unesi id ili jmbg");

        verify(currencyRepository, never()).findByOznaka(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void searchAllAccountsReturnsDtosSortedByLastName() {
        ClientResponseDto zika = new ClientResponseDto(1L, "Zika", "Zoric", 0L, Pol.M, "zika@test.com", "1", "A");
        ClientResponseDto ana = new ClientResponseDto(2L, "Ana", "Andric", 0L, Pol.Z, "ana@test.com", "2", "B");
        CheckingAccount checkingAccount = new CheckingAccount(AccountConcrete.STANDARDNI);
        checkingAccount.setBrojRacuna("ACC-2");
        checkingAccount.setVlasnik(1L);
        FxAccount fxAccount = new FxAccount(AccountOwnershipType.PERSONAL);
        fxAccount.setBrojRacuna("ACC-1");
        fxAccount.setVlasnik(2L);

        when(clientService.searchClients("Ime", "Prezime", 0, 10))
                .thenReturn(new PageImpl<>(List.of(zika, ana), PageRequest.of(0, 10), 2));
        when(accountRepository.searchAccounts(eq("ACC"), anyList(), eq(PageRequest.of(0, 10))))
                .thenReturn(new PageImpl<>(List.of(checkingAccount, fxAccount), PageRequest.of(0, 10), 2));

        Page<?> result = service.searchAllAccounts(jwtWithEmployeeId(901L), "Ime", "Prezime", "ACC", 0, 10);

        assertThat(result.getContent())
                .extracting("prezime")
                .containsExactly("Andric", "Zoric");
        assertThat(result.getContent())
                .extracting("tekuciIliDevizni")
                .containsExactly("devizni", "tekuci");
    }

    private Jwt jwtWithEmployeeId(Long employeeId) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("employeeId", employeeId)
                .build();
    }
}
