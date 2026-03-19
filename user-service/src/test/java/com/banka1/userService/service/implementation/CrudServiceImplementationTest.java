package com.banka1.userService.service.implementation;

import com.banka1.userService.domain.ConfirmationToken;
import com.banka1.userService.domain.Zaposlen;
import com.banka1.userService.domain.enums.Pol;
import com.banka1.userService.domain.enums.Role;
import com.banka1.userService.dto.requests.EmployeeCreateRequestDto;
import com.banka1.userService.dto.requests.EmployeeEditRequestDto;
import com.banka1.userService.dto.requests.EmployeeUpdateRequestDto;
import com.banka1.userService.dto.responses.EmployeeResponseDto;
import com.banka1.userService.exception.BusinessException;
import com.banka1.userService.exception.ErrorCode;
import com.banka1.userService.mappers.EmployeeMapper;
import com.banka1.userService.rabbitMQ.RabbitClient;
import com.banka1.userService.repository.ConfirmationTokenRepository;
import com.banka1.userService.repository.ZaposlenRepository;
import com.banka1.userService.security.JWTService;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrudServiceImplementationTest {

    @Mock
    private ZaposlenRepository zaposlenRepository;

    @Mock
    private JWTService jwtService;

    @Mock
    private ConfirmationTokenRepository confirmationTokenRepository;

    @Mock
    private RabbitClient rabbitClient;

    @Mock
    private EmployeeMapper employeeMapper;

    @InjectMocks
    private CrudServiceImplementation crudService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(crudService, "role", "roles");
        ReflectionTestUtils.setField(crudService, "activateAccount", "http://localhost/activate?token=");
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createEmployeeThrowsWhenEmailAlreadyExists() {
        EmployeeCreateRequestDto request = createRequest();
        when(zaposlenRepository.existsByEmail("nikola@banka.com")).thenReturn(true);

        assertThatThrownBy(() -> crudService.createEmployee(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Email: nikola@banka.com");
    }

    @Test
    void createEmployeeThrowsForDuplicateUsername() {
        EmployeeCreateRequestDto request = createRequest();
        when(zaposlenRepository.existsByEmail("nikola@banka.com")).thenReturn(false);
        when(zaposlenRepository.existsByUsername("nikola")).thenReturn(true);

        assertThatThrownBy(() -> crudService.createEmployee(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Username: nikola");
    }

    @Test
    void createEmployeeThrowsWhenUserTooYoung() {
        EmployeeCreateRequestDto request = createRequest();
        request.setDatumRodjenja(LocalDate.now().minusYears(17));

        when(zaposlenRepository.existsByEmail("nikola@banka.com")).thenReturn(false);
        when(zaposlenRepository.existsByUsername("nikola")).thenReturn(false);

        assertThatThrownBy(() -> crudService.createEmployee(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_TOO_YOUNG);
    }

    @Test
    void createEmployeeCreatesUserTokenAndResponse() {
        EmployeeCreateRequestDto request = createRequest();
        Zaposlen mapped = employee("nikola@banka.com", "nikola", Role.BASIC);
        Zaposlen saved = employee("nikola@banka.com", "nikola", Role.BASIC);
        saved.setId(9L);
        EmployeeResponseDto responseDto = new EmployeeResponseDto(9L, "Nikola", "Nikolic", "nikola@banka.com", "nikola", "Agent", "Prodaja", true, Role.BASIC);

        when(zaposlenRepository.existsByEmail("nikola@banka.com")).thenReturn(false);
        when(zaposlenRepository.existsByUsername("nikola")).thenReturn(false);
        when(employeeMapper.toEntity(request)).thenReturn(mapped);
        when(zaposlenRepository.save(mapped)).thenReturn(saved);
        when(jwtService.generateRandomToken()).thenReturn("plain-token");
        when(jwtService.sha256Hex("plain-token")).thenReturn("hashed-token");
        when(confirmationTokenRepository.save(any(ConfirmationToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(employeeMapper.toDto(saved)).thenReturn(responseDto);

        EmployeeResponseDto result = crudService.createEmployee(request);

        assertThat(result.getId()).isEqualTo(9L);
        assertThat(saved.getConfirmationToken()).isNotNull();
        assertThat(saved.getConfirmationToken().getValue()).isEqualTo("hashed-token");
        verify(rabbitClient, never()).sendEmailNotification(any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void searchEmployeesNormalizesNullFiltersToEmptyStrings() {
        Zaposlen emp = employee("ana@banka.com", "ana", Role.AGENT);
        EmployeeResponseDto responseDto = new EmployeeResponseDto(1L, "Ana", "Anic", "ana@banka.com", "ana", "Broker", "Prodaja", true, Role.AGENT);
        PageRequest pageable = PageRequest.of(0, 10);

        when(zaposlenRepository.searchEmployees("", "", "", "", "", pageable))
                .thenReturn(new PageImpl<>(List.of(emp), pageable, 1));
        when(employeeMapper.toDto(emp)).thenReturn(responseDto);

        Page<EmployeeResponseDto> result = crudService.searchEmployees(null, null, null, null, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getEmail()).isEqualTo("ana@banka.com");
    }

    @Test
    void searchEmployeesEscapesLikeMetacharacters() {
        PageRequest pageable = PageRequest.of(0, 10);

        when(zaposlenRepository.searchEmployees("100\\%", "", "", "", "", pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<EmployeeResponseDto> result = crudService.searchEmployees("100%", null, null, null, null, pageable);

        assertThat(result.getContent()).isEmpty();
        verify(zaposlenRepository).searchEmployees(eq("100\\%"), eq(""), eq(""), eq(""), eq(""), eq(pageable));
    }

    @Test
    void updateEmployeeThrowsWhenCallerRoleIsNotStrongEnough() {
        Zaposlen emp = employee("ana@banka.com", "ana", Role.AGENT);
        EmployeeUpdateRequestDto request = new EmployeeUpdateRequestDto();
        Jwt jwt = jwtWithClaims(Map.of("roles", "AGENT"));

        when(zaposlenRepository.findById(1L)).thenReturn(Optional.of(emp));

        assertThatThrownBy(() -> crudService.updateEmployee(jwt, 1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Slab si");
    }

    @Test
    void updateEmployeeThrowsWhenEmployeeNotFound() {
        EmployeeUpdateRequestDto request = new EmployeeUpdateRequestDto();
        Jwt jwt = jwtWithClaims(Map.of("roles", "ADMIN"));

        when(zaposlenRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> crudService.updateEmployee(jwt, 99L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void updateEmployeeSucceedsWhenCallerHasStrongRole() {
        Zaposlen emp = employee("ana@banka.com", "ana", Role.BASIC);
        EmployeeUpdateRequestDto request = new EmployeeUpdateRequestDto();
        request.setDepartman("IT");
        EmployeeResponseDto responseDto = new EmployeeResponseDto(1L, "Ana", "Anic", "ana@banka.com", "ana", "Broker", "IT", true, Role.BASIC);
        Jwt jwt = jwtWithClaims(Map.of("roles", "ADMIN"));

        when(zaposlenRepository.findById(1L)).thenReturn(Optional.of(emp));
        when(zaposlenRepository.save(emp)).thenReturn(emp);
        when(employeeMapper.toDto(emp)).thenReturn(responseDto);

        EmployeeResponseDto result = crudService.updateEmployee(jwt, 1L, request);

        assertThat(result.getDepartman()).isEqualTo("IT");
    }

    @Test
    void updateEmployeeRegistersAfterCommitCallbackWhenDeactivating() {
        Zaposlen emp = employee("ana@banka.com", "ana", Role.BASIC);
        EmployeeUpdateRequestDto request = new EmployeeUpdateRequestDto();
        request.setAktivan(false);
        EmployeeResponseDto responseDto = new EmployeeResponseDto(1L, "Ana", "Anic", "ana@banka.com", "ana", "Agent", "Prodaja", false, Role.BASIC);
        Jwt jwt = jwtWithClaims(Map.of("roles", "ADMIN"));

        when(zaposlenRepository.findById(1L)).thenReturn(Optional.of(emp));
        when(zaposlenRepository.save(emp)).thenReturn(emp);
        when(employeeMapper.toDto(emp)).thenReturn(responseDto);

        crudService.updateEmployee(jwt, 1L, request);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void editEmployeeUpdatesCurrentUserByJwtId() {
        Zaposlen emp = employee("ana@banka.com", "ana", Role.AGENT);
        EmployeeEditRequestDto request = new EmployeeEditRequestDto("Ana", "Anic", "123", "Adresa", "Senior Agent", "IT");
        EmployeeResponseDto responseDto = new EmployeeResponseDto(1L, "Ana", "Anic", "ana@banka.com", "ana", "Senior Agent", "IT", true, Role.AGENT);
        Jwt jwt = jwtWithClaims(Map.of("id", 1L));

        when(zaposlenRepository.findById(1L)).thenReturn(Optional.of(emp));
        when(zaposlenRepository.save(emp)).thenReturn(emp);
        when(employeeMapper.toDto(emp)).thenReturn(responseDto);

        EmployeeResponseDto result = crudService.editEmployee(jwt, request);

        assertThat(result.getPozicija()).isEqualTo("Senior Agent");
        verify(employeeMapper).updateEntityFromDto(emp, request);
    }

    @Test
    void globalSearchEmployeesMapsRepositoryResults() {
        Zaposlen emp = employee("ana@banka.com", "ana", Role.AGENT);
        EmployeeResponseDto responseDto = new EmployeeResponseDto(1L, "Ana", "Anic", "ana@banka.com", "ana", "Broker", "Prodaja", true, Role.AGENT);
        PageRequest pageable = PageRequest.of(0, 10);

        when(zaposlenRepository.globalSearchEmployees("ana", pageable))
                .thenReturn(new PageImpl<>(List.of(emp), pageable, 1));
        when(employeeMapper.toDto(emp)).thenReturn(responseDto);

        Page<EmployeeResponseDto> result = crudService.globalSearchEmployees("ana", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getUsername()).isEqualTo("ana");
    }

    @Test
    void globalSearchNullQueryUsesEmptyString() {
        PageRequest pageable = PageRequest.of(0, 10);

        when(zaposlenRepository.globalSearchEmployees("", pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        crudService.globalSearchEmployees(null, pageable);

        verify(zaposlenRepository).globalSearchEmployees("", pageable);
    }

    @Test
    void editEmployeeThrowsWhenJwtMissingIdClaim() {
        EmployeeEditRequestDto request = new EmployeeEditRequestDto("Ana", "Anic", "123", "Adresa", "Senior Agent", "IT");
        Jwt jwt = jwtWithClaims(Map.of("roles", "BASIC"));

        assertThatThrownBy(() -> crudService.editEmployee(jwt, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void deleteEmployeeSoftDeletesAndRegistersEmailCallback() {
        Zaposlen emp = employee("ana@banka.com", "ana", Role.BASIC);
        Jwt jwt = jwtWithClaims(Map.of("roles", "ADMIN"));

        when(zaposlenRepository.findById(1L)).thenReturn(Optional.of(emp));

        crudService.deleteEmployee(1L, jwt);

        verify(zaposlenRepository).delete(emp);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    @Test
    void deleteEmployeeThrowsWhenNotFound() {
        Jwt jwt = mock(Jwt.class);

        when(zaposlenRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> crudService.deleteEmployee(99L, jwt))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void updateEmployeeDoesNotRegisterCallbackWhenAktivanIsNull() {
        Zaposlen emp = employee("ana@banka.com", "ana", Role.BASIC);
        EmployeeUpdateRequestDto request = new EmployeeUpdateRequestDto();
        request.setDepartman("HR");
        EmployeeResponseDto responseDto = new EmployeeResponseDto(1L, "Ana", "Anic", "ana@banka.com", "ana", "Broker", "HR", true, Role.BASIC);
        Jwt jwt = jwtWithClaims(Map.of("roles", "ADMIN"));

        when(zaposlenRepository.findById(1L)).thenReturn(Optional.of(emp));
        when(zaposlenRepository.save(emp)).thenReturn(emp);
        when(employeeMapper.toDto(emp)).thenReturn(responseDto);

        crudService.updateEmployee(jwt, 1L, request);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void updateEmployeeDoesNotRegisterCallbackWhenAktivanIsTrue() {
        Zaposlen emp = employee("ana@banka.com", "ana", Role.BASIC);
        EmployeeUpdateRequestDto request = new EmployeeUpdateRequestDto();
        request.setAktivan(true);
        EmployeeResponseDto responseDto = new EmployeeResponseDto(1L, "Ana", "Anic", "ana@banka.com", "ana", "Broker", "Prodaja", true, Role.BASIC);
        Jwt jwt = jwtWithClaims(Map.of("roles", "ADMIN"));

        when(zaposlenRepository.findById(1L)).thenReturn(Optional.of(emp));
        when(zaposlenRepository.save(emp)).thenReturn(emp);
        when(employeeMapper.toDto(emp)).thenReturn(responseDto);

        crudService.updateEmployee(jwt, 1L, request);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void globalSearchEmployeesEscapesLikeMetacharacters() {
        PageRequest pageable = PageRequest.of(0, 10);

        when(zaposlenRepository.globalSearchEmployees("ana\\%test", pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        crudService.globalSearchEmployees("ana%test", pageable);

        verify(zaposlenRepository).globalSearchEmployees("ana\\%test", pageable);
    }

    @Test
    void globalSearchEmployeesEscapesUnderscoreInQuery() {
        PageRequest pageable = PageRequest.of(0, 10);

        when(zaposlenRepository.globalSearchEmployees("ana\\_test", pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        crudService.globalSearchEmployees("ana_test", pageable);

        verify(zaposlenRepository).globalSearchEmployees("ana\\_test", pageable);
    }

    @Test
    void editEmployeeThrowsWhenEmployeeNotFoundById() {
        EmployeeEditRequestDto request = new EmployeeEditRequestDto("Ana", "Anic", "123", "Adresa", "Agent", "IT");
        Jwt jwt = jwtWithClaims(Map.of("id", 99L));

        when(zaposlenRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> crudService.editEmployee(jwt, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void createEmployeeDoesNotSendEmailDirectlyBeforeCommit() {
        EmployeeCreateRequestDto request = createRequest();
        Zaposlen mapped = employee("nikola@banka.com", "nikola", Role.BASIC);
        Zaposlen saved = employee("nikola@banka.com", "nikola", Role.BASIC);
        saved.setId(9L);
        EmployeeResponseDto responseDto = new EmployeeResponseDto(9L, "Nikola", "Nikolic", "nikola@banka.com", "nikola", "Agent", "Prodaja", true, Role.BASIC);

        when(zaposlenRepository.existsByEmail("nikola@banka.com")).thenReturn(false);
        when(zaposlenRepository.existsByUsername("nikola")).thenReturn(false);
        when(employeeMapper.toEntity(request)).thenReturn(mapped);
        when(zaposlenRepository.save(mapped)).thenReturn(saved);
        when(jwtService.generateRandomToken()).thenReturn("plain-token");
        when(jwtService.sha256Hex("plain-token")).thenReturn("hashed-token");
        when(confirmationTokenRepository.save(any(ConfirmationToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(employeeMapper.toDto(saved)).thenReturn(responseDto);

        crudService.createEmployee(request);

        verify(rabbitClient, never()).sendEmailNotification(any());
    }

    private EmployeeCreateRequestDto createRequest() {
        EmployeeCreateRequestDto request = new EmployeeCreateRequestDto();
        request.setIme("Nikola");
        request.setPrezime("Nikolic");
        request.setDatumRodjenja(LocalDate.of(1995, 5, 5));
        request.setPol(Pol.M);
        request.setEmail("nikola@banka.com");
        request.setUsername("nikola");
        request.setPozicija("Agent");
        request.setDepartman("Prodaja");
        request.setRole(Role.BASIC);
        return request;
    }

    private Zaposlen employee(String email, String username, Role role) {
        Zaposlen emp = new Zaposlen();
        emp.setId(1L);
        emp.setIme("Ana");
        emp.setPrezime("Anic");
        emp.setDatumRodjenja(LocalDate.of(1991, 1, 1));
        emp.setPol(Pol.M);
        emp.setEmail(email);
        emp.setUsername(username);
        emp.setPozicija("Broker");
        emp.setDepartman("Prodaja");
        emp.setAktivan(true);
        emp.setRole(role);
        return emp;
    }

    private Jwt jwtWithClaims(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "none"), claims);
    }
}
