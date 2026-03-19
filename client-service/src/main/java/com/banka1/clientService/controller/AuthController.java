package com.banka1.clientService.controller;

import com.banka1.clientService.dto.requests.LoginRequestDto;
import com.banka1.clientService.dto.responses.LoginResponseDto;
import com.banka1.clientService.service.AuthService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST kontroler koji izlaze endpoint-e za autentifikaciju klijenata.
 * Svi endpoint-i su dostupni pod baznom putanjom {@code /auth}.
 * Login endpoint je javno dostupan i ne zahteva prethodni JWT token.
 */
@RestController
@RequestMapping("/auth")
@AllArgsConstructor
public class AuthController {

    /** Servis koji sadrzi logiku autentifikacije klijenata. */
    private final AuthService authService;

    /**
     * Autentifikuje klijenta i vraca JWT pristupni token.
     *
     * @param dto podaci za prijavljivanje (email i lozinka)
     * @return JWT token sa statusom 200 OK
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(@RequestBody @Valid LoginRequestDto dto) {
        return ResponseEntity.ok(authService.login(dto));
    }
}
