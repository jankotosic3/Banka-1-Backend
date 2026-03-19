package com.banka1.clientService.service;

import com.banka1.clientService.dto.requests.ActivateDto;
import com.banka1.clientService.dto.requests.ForgotPasswordDto;
import com.banka1.clientService.dto.requests.LoginRequestDto;
import com.banka1.clientService.dto.responses.LoginResponseDto;

/**
 * Servis koji upravlja autentifikacijom i zivotnim ciklusom naloga klijenata.
 */
public interface AuthService {

    /**
     * Autentifikuje klijenta na osnovu email adrese i lozinke i vraca JWT token.
     *
     * @param dto podaci za prijavljivanje
     * @return odgovor sa JWT pristupnim tokenom
     */
    LoginResponseDto login(LoginRequestDto dto);

    /**
     * Proverava da li je confirmation token validan i vraca ID tokena.
     *
     * @param confirmationToken token primljen putem email linka
     * @return ID entiteta {@code ClientConfirmationToken}
     */
    Long check(String confirmationToken);

    /**
     * Menja lozinku klijenta; opciono i aktivira nalog.
     *
     * @param activateDto podaci sa ID-em potvrde, tokenom i novom lozinkom
     * @param aktiviraj   {@code true} za aktivaciju naloga, {@code false} za reset lozinke
     * @return poruka o uspesnom zavrsetku operacije
     */
    String editPassword(ActivateDto activateDto, boolean aktiviraj);

    /**
     * Generise i salje token za reset lozinke na email klijenta.
     *
     * @param dto zahtev sa email adresom klijenta
     * @return poruka o rezultatu operacije
     */
    String forgotPassword(ForgotPasswordDto dto);

    /**
     * Ponovo salje aktivacioni mejl za nalog koji jos nije aktiviran.
     *
     * @param email email adresa klijenta
     * @return poruka o rezultatu operacije
     */
    String resendActivation(String email);
}
