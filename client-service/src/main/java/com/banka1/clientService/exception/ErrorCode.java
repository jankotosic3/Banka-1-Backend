package com.banka1.clientService.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Enum koji centralizuje sve poslovne greske aplikacije.
 * Svaka konstanta nosi HTTP status, masinsko-citljivi kod i kratak naslov.
 */
@Getter
public enum ErrorCode {

    // ── Greske vezane za klijenta (ERR_CLIENT_xxx) ──────────────────────────

    /** Klijent sa trazenim identifikatorom nije pronadjen u bazi. */
    CLIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "ERR_CLIENT_001", "Klijent nije pronađen"),

    /** Pokusaj kreiranja klijenta sa email adresom koja vec postoji. */
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "ERR_CLIENT_002", "Email adresa je već u upotrebi"),

    /** Pokusaj kreiranja klijenta sa JMBG-om koji vec postoji. */
    JMBG_ALREADY_EXISTS(HttpStatus.CONFLICT, "ERR_CLIENT_003", "JMBG je već u upotrebi"),

    /** Klijent sa zadatim JMBG-om nije pronadjen. */
    JMBG_NOT_FOUND(HttpStatus.NOT_FOUND, "ERR_CLIENT_004", "Klijent sa zadatim JMBG-om nije pronađen"),

    // ── Autorizacione greske (ERR_AUTH_xxx) ─────────────────────────────────

    /** Nevalidan JWT token. */
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "ERR_AUTH_001", "Neispravan token"),

    /** Pogresni kredencijali pri logovanju. */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "ERR_AUTH_002", "Pogrešan email ili lozinka"),

    /** Klijent nije aktivirao nalog. */
    USER_INACTIVE(HttpStatus.FORBIDDEN, "ERR_AUTH_003", "Klijent nije aktivan"),

    /** Klijent sa zadatim emailom nije pronadjen (koristi se u forgot-password). */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "ERR_AUTH_004", "Korisnik nije pronađen");

    /** HTTP status koji se vraca klijentu kada se baci ova greska. */
    private final HttpStatus httpStatus;

    /** Stabilan masinsko-citljivi identifikator greske. */
    private final String code;

    /** Kratak ljudski citljivi naslov greske. */
    private final String title;

    ErrorCode(HttpStatus httpStatus, String code, String title) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.title = title;
    }
}
