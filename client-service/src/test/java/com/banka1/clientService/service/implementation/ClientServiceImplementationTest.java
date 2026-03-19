package com.banka1.clientService.service.implementation;

import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.domain.enums.ClientRole;
import com.banka1.clientService.domain.enums.Pol;
import com.banka1.clientService.dto.requests.ClientCreateRequestDto;
import com.banka1.clientService.dto.requests.ClientUpdateRequestDto;
import com.banka1.clientService.dto.responses.ClientInfoResponseDto;
import com.banka1.clientService.dto.responses.ClientResponseDto;
import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import com.banka1.clientService.mappers.ClientMapper;
import com.banka1.clientService.rabbitMQ.RabbitClient;
import com.banka1.clientService.repository.KlijentRepository;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientServiceImplementationTest {

    @Mock
    private KlijentRepository klijentRepository;

    @Mock
    private ClientMapper clientMapper;

    @Mock
    private RabbitClient rabbitClient;

    @InjectMocks
    private ClientServiceImplementation clientService;

    @BeforeEach
    void initTransactionSync() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearTransactionSync() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ── createClient ─────────────────────────────────────────────────────────

    @Test
    void createClientSuccessfullyCreatesAndReturnsDto() {
        ClientCreateRequestDto dto = createRequest();
        Klijent entity = klijent("marko@banka.com", "1234567890123");
        Klijent saved = klijent("marko@banka.com", "1234567890123");
        saved.setId(42L);
        ClientResponseDto responseDto = responseDto(42L, "Marko", "Markovic", "marko@banka.com");

        when(clientMapper.toEntity(dto)).thenReturn(entity);
        when(klijentRepository.save(entity)).thenReturn(saved);
        when(clientMapper.toDto(saved)).thenReturn(responseDto);

        ClientResponseDto result = clientService.createClient(dto);

        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getEmail()).isEqualTo("marko@banka.com");
        verify(klijentRepository).save(entity);
    }

    @Test
    void createClientRegistersAfterCommitNotification() {
        ClientCreateRequestDto dto = createRequest();
        Klijent entity = klijent("marko@banka.com", "1234567890123");
        Klijent saved = klijent("marko@banka.com", "1234567890123");
        saved.setId(1L);

        when(clientMapper.toEntity(dto)).thenReturn(entity);
        when(klijentRepository.save(entity)).thenReturn(saved);
        when(clientMapper.toDto(saved)).thenReturn(responseDto(1L, "Marko", "Markovic", "marko@banka.com"));

        clientService.createClient(dto);

        // Synchronization callbacks are registered; trigger afterCommit manually to verify rabbit call
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCommit());

        verify(rabbitClient).sendEmailNotification(any());
    }

    // ── searchClients ────────────────────────────────────────────────────────

    @Test
    void searchClientsNullFiltersBecomesEmptyStringsAndCallsRepository() {
        PageRequest pageable = PageRequest.of(0, 10);
        Klijent entity = klijent("marko@banka.com", "1234567890123");
        ClientResponseDto responseDto = responseDto(1L, "Marko", "Markovic", "marko@banka.com");

        when(klijentRepository.searchClients("", "", "", pageable))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));
        when(clientMapper.toDto(entity)).thenReturn(responseDto);

        Page<ClientResponseDto> result = clientService.searchClients(null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getEmail()).isEqualTo("marko@banka.com");
        verify(klijentRepository).searchClients("", "", "", pageable);
    }

    @Test
    void searchClientsWithNonNullEmailPassesEscapedEmailToRepository() {
        PageRequest pageable = PageRequest.of(0, 10);

        when(klijentRepository.searchClients("", "", "test\\%", pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        clientService.searchClients(null, null, "test%", pageable);

        verify(klijentRepository).searchClients(eq(""), eq(""), eq("test\\%"), eq(pageable));
    }

    @Test
    void searchClientsEscapesLikeMetacharactersInFilters() {
        PageRequest pageable = PageRequest.of(0, 10);

        when(klijentRepository.searchClients("mark\\_o", "100\\%", "", pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        clientService.searchClients("mark_o", "100%", null, pageable);

        verify(klijentRepository).searchClients(eq("mark\\_o"), eq("100\\%"), eq(""), eq(pageable));
    }

    // ── globalSearchClients ──────────────────────────────────────────────────

    @Test
    void globalSearchClientsNullQueryBecomesEmptyString() {
        PageRequest pageable = PageRequest.of(0, 10);

        when(klijentRepository.globalSearchClients("", pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        clientService.globalSearchClients(null, pageable);

        verify(klijentRepository).globalSearchClients("", pageable);
    }

    @Test
    void globalSearchClientsMapsRepositoryResults() {
        PageRequest pageable = PageRequest.of(0, 10);
        Klijent entity = klijent("marko@banka.com", "1234567890123");
        ClientResponseDto responseDto = responseDto(1L, "Marko", "Markovic", "marko@banka.com");

        when(klijentRepository.globalSearchClients("marko", pageable))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));
        when(clientMapper.toDto(entity)).thenReturn(responseDto);

        Page<ClientResponseDto> result = clientService.globalSearchClients("marko", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getIme()).isEqualTo("Marko");
    }

    // ── updateClient ─────────────────────────────────────────────────────────

    @Test
    void updateClientThrowsWhenClientNotFound() {
        ClientUpdateRequestDto dto = new ClientUpdateRequestDto();
        when(klijentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> clientService.updateClient(99L, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);
    }

    @Test
    void updateClientSuccessfullyUpdatesFields() {
        Klijent existing = klijent("marko@banka.com", "1234567890123");
        existing.setId(1L);
        ClientUpdateRequestDto dto = new ClientUpdateRequestDto();
        dto.setIme("Marko");
        dto.setPrezime("Novakovic");
        ClientResponseDto responseDto = responseDto(1L, "Marko", "Novakovic", "marko@banka.com");

        when(klijentRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(klijentRepository.saveAndFlush(existing)).thenReturn(existing);
        when(clientMapper.toDto(existing)).thenReturn(responseDto);

        ClientResponseDto result = clientService.updateClient(1L, dto);

        assertThat(result.getPrezime()).isEqualTo("Novakovic");
        verify(clientMapper).updateEntityFromDto(existing, dto);
        verify(klijentRepository).saveAndFlush(existing);
    }

    @Test
    void updateClientDoesNotChangeJmbgOrPassword() {
        Klijent existing = klijent("marko@banka.com", "1234567890123");
        existing.setId(1L);
        existing.setPassword("original-hash");
        String originalJmbg = existing.getJmbg();
        String originalPassword = existing.getPassword();

        ClientUpdateRequestDto dto = new ClientUpdateRequestDto();
        // ClientUpdateRequestDto intentionally has no jmbg/password fields;
        // we verify the entity is untouched after updateEntityFromDto is called
        ClientResponseDto responseDto = responseDto(1L, "Marko", "Markovic", "marko@banka.com");

        when(klijentRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(klijentRepository.saveAndFlush(existing)).thenReturn(existing);
        when(clientMapper.toDto(existing)).thenReturn(responseDto);

        clientService.updateClient(1L, dto);

        // The real mapper never touches jmbg or password – assert the entity still holds original values
        assertThat(existing.getJmbg()).isEqualTo(originalJmbg);
        assertThat(existing.getPassword()).isEqualTo(originalPassword);
    }

    // ── deleteClient ─────────────────────────────────────────────────────────

    @Test
    void deleteClientThrowsWhenClientNotFound() {
        when(klijentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> clientService.deleteClient(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CLIENT_NOT_FOUND);

        verify(klijentRepository, never()).delete(any(Klijent.class));
    }

    @Test
    void deleteClientSuccessfullySoftDeletesClient() {
        Klijent existing = klijent("marko@banka.com", "1234567890123");
        existing.setId(1L);

        when(klijentRepository.findById(1L)).thenReturn(Optional.of(existing));

        clientService.deleteClient(1L);

        verify(klijentRepository).delete(existing);
    }

    // ── getIdByJmbg ──────────────────────────────────────────────────────────

    @Test
    void getClientIdByJmbgThrowsWhenNotFound() {
        when(klijentRepository.findByJmbg("9999999999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> clientService.getInfoByJmbg("9999999999999"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.JMBG_NOT_FOUND);
    }

    @Test
    void getClientIdByJmbgReturnsIdWhenFound() {
        Klijent existing = klijent("marko@banka.com", "1234567890123");
        existing.setId(7L);

        when(klijentRepository.findByJmbg("1234567890123")).thenReturn(Optional.of(existing));

        ClientInfoResponseDto result = clientService.getInfoByJmbg("1234567890123");

        assertThat(result.getId()).isEqualTo(7L);
    }

    @Test
    void updateClientThrowsEmailAlreadyExistsWhenEmailTaken() {
        Klijent existing = klijent("marko@banka.com", "1234567890123");
        existing.setId(1L);
        ClientUpdateRequestDto dto = new ClientUpdateRequestDto();
        dto.setEmail("taken@banka.com");

        when(klijentRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(klijentRepository.existsByEmailAndIdNot("taken@banka.com", 1L)).thenReturn(true);

        assertThatThrownBy(() -> clientService.updateClient(1L, dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);

        verify(klijentRepository, never()).saveAndFlush(any());
    }

    @Test
    void createClientAfterCompletionRollbackDoesNotSendEmail() {
        ClientCreateRequestDto dto = createRequest();
        Klijent entity = klijent("marko@banka.com", "1234567890123");
        Klijent saved = klijent("marko@banka.com", "1234567890123");
        saved.setId(1L);

        when(clientMapper.toEntity(dto)).thenReturn(entity);
        when(klijentRepository.save(entity)).thenReturn(saved);
        when(clientMapper.toDto(saved)).thenReturn(responseDto(1L, "Marko", "Markovic", "marko@banka.com"));

        clientService.createClient(dto);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(rabbitClient, never()).sendEmailNotification(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ClientCreateRequestDto createRequest() {
        ClientCreateRequestDto dto = new ClientCreateRequestDto();
        dto.setIme("Marko");
        dto.setPrezime("Markovic");
        dto.setDatumRodjenja(946684800000L); // 2000-01-01 in ms
        dto.setPol(Pol.M);
        dto.setEmail("marko@banka.com");
        dto.setBrojTelefona("+38160123456");
        dto.setAdresa("Ulica bb, Beograd");
        dto.setJmbg("1234567890123");
        return dto;
    }

    private Klijent klijent(String email, String jmbg) {
        Klijent k = new Klijent();
        k.setIme("Marko");
        k.setPrezime("Markovic");
        k.setDatumRodjenja(946684800000L);
        k.setPol(Pol.M);
        k.setEmail(email);
        k.setBrojTelefona("+38160123456");
        k.setAdresa("Ulica bb, Beograd");
        k.setJmbg(jmbg);
        return k;
    }

    private ClientResponseDto responseDto(Long id, String ime, String prezime, String email) {
        return new ClientResponseDto(id, ime, prezime, 946684800000L, Pol.M, email, "+38160123456", "Ulica bb, Beograd", ClientRole.CLIENT_BASIC);
    }
}
