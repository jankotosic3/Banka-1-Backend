package com.banka1.clientService.controller;

import com.banka1.clientService.dto.requests.ActivateDto;
import com.banka1.clientService.dto.requests.ForgotPasswordDto;
import com.banka1.clientService.dto.requests.LoginRequestDto;
import com.banka1.clientService.dto.responses.CheckActivateDto;
import com.banka1.clientService.dto.responses.LoginResponseDto;
import com.banka1.clientService.service.AuthService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST kontroler koji izlaze endpoint-e za autentifikaciju i upravljanje nalogom klijenata.
 * Svi endpoint-i su dostupni pod baznom putanjom {@code /auth}.
 */
@RestController
@RequestMapping("/auth")
@AllArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(@RequestBody @Valid LoginRequestDto dto) {
        return ResponseEntity.ok(authService.login(dto));
    }

    @GetMapping("/check-activate")
    public ResponseEntity<CheckActivateDto> checkActivate(@RequestParam String token) {
        return ResponseEntity.ok(new CheckActivateDto(authService.check(token)));
    }

    @PostMapping("/activate")
    public ResponseEntity<String> activate(@RequestBody @Valid ActivateDto dto) {
        return ResponseEntity.ok(authService.editPassword(dto, true));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@RequestBody @Valid ActivateDto dto) {
        return ResponseEntity.ok(authService.editPassword(dto, false));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestBody @Valid ForgotPasswordDto dto) {
        return ResponseEntity.ok(authService.forgotPassword(dto));
    }

    @PostMapping("/resend-activation")
    public ResponseEntity<String> resendActivation(@RequestBody @Valid ForgotPasswordDto dto) {
        return ResponseEntity.ok(authService.resendActivation(dto.getEmail()));
    }
}
