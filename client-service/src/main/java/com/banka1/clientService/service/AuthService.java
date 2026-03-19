package com.banka1.clientService.service;

import com.banka1.clientService.dto.requests.LoginRequestDto;
import com.banka1.clientService.dto.responses.LoginResponseDto;

/**
 * Servis koji upravlja autentifikacijom klijenata.
 */
public interface AuthService {

    /**
     * Autentifikuje klijenta na osnovu email adrese i lozinke i vraca JWT token.
     *
     * @param dto podaci za prijavljivanje
     * @return odgovor sa JWT pristupnim tokenom
     */
    LoginResponseDto login(LoginRequestDto dto);
}
