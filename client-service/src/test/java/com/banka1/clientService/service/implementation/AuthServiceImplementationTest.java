package com.banka1.clientService.service.implementation;

import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.domain.enums.ClientRole;
import com.banka1.clientService.domain.enums.Pol;
import com.banka1.clientService.dto.requests.LoginRequestDto;
import com.banka1.clientService.dto.responses.LoginResponseDto;
import com.banka1.clientService.exception.BusinessException;
import com.banka1.clientService.exception.ErrorCode;
import com.banka1.clientService.repository.KlijentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplementationTest {

    @Mock
    private KlijentRepository klijentRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthServiceImplementation authService;

    @BeforeEach
    void setUp() throws Exception {
        authService = new AuthServiceImplementation(
                klijentRepository,
                passwordEncoder,
                "test_secret_key_at_least_32_characters_long"
        );
        ReflectionTestUtils.setField(authService, "rolesClaim", "roles");
        ReflectionTestUtils.setField(authService, "idClaim", "id");
        ReflectionTestUtils.setField(authService, "issuer", "banka1");
        ReflectionTestUtils.setField(authService, "expirationTime", 3600000L);
    }

    @Test
    void loginSuccessReturnsToken() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "lozinka123");
        Klijent klijent = klijent("marko@banka.com", "hashed");

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("lozinka123", "hashed")).thenReturn(true);

        LoginResponseDto response = authService.login(dto);

        assertThat(response.getToken()).isNotBlank();
    }

    @Test
    void loginEmailNotFoundThrowsInvalidCredentials() {
        LoginRequestDto dto = loginRequest("nepostoji@banka.com", "lozinka123");

        when(klijentRepository.findByEmail("nepostoji@banka.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginWrongPasswordThrowsInvalidCredentials() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "pogresna");
        Klijent klijent = klijent("marko@banka.com", "hashed");

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("pogresna", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginNullPasswordThrowsInvalidCredentials() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "bilo_sta");
        Klijent klijent = klijent("marko@banka.com", null);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));

        assertThatThrownBy(() -> authService.login(dto))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void loginSuccessTokenContainsCorrectRole() {
        LoginRequestDto dto = loginRequest("marko@banka.com", "lozinka123");
        Klijent klijent = klijent("marko@banka.com", "hashed");
        klijent.setRole(ClientRole.CLIENT_TRADING);

        when(klijentRepository.findByEmail("marko@banka.com")).thenReturn(Optional.of(klijent));
        when(passwordEncoder.matches("lozinka123", "hashed")).thenReturn(true);

        LoginResponseDto response = authService.login(dto);

        // JWT je u formatu header.payload.signature — dekodujemo payload
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(response.getToken().split("\\.")[1]));
        assertThat(payload).contains("CLIENT_TRADING");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LoginRequestDto loginRequest(String email, String password) {
        LoginRequestDto dto = new LoginRequestDto();
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }

    private Klijent klijent(String email, String password) {
        Klijent k = new Klijent();
        k.setId(1L);
        k.setIme("Marko");
        k.setPrezime("Markovic");
        k.setDatumRodjenja(946684800000L);
        k.setPol(Pol.M);
        k.setEmail(email);
        k.setJmbg("1234567890123");
        k.setPassword(password);
        k.setRole(ClientRole.CLIENT_BASIC);
        return k;
    }
}
