package com.banka1.clientService.dto.requests;

import com.banka1.clientService.domain.enums.Pol;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO koji zaposleni koristi za azuriranje podataka klijenta.
 * Ime i prezime su obavezna polja; ostala polja su opciona.
 * Password i JMBG se ne mogu menjati.
 */
@Getter
@Setter
public class ClientUpdateRequestDto {

    /** Novo ime klijenta. */
    private String ime;

    /** Novo prezime klijenta. */
    private String prezime;

    /** Novi datum rodjenja klijenta kao Unix timestamp. */
    private Long datumRodjenja;

    /** Novi pol klijenta. */
    private Pol pol;

    /**
     * Nova email adresa klijenta.
     * Proverava se jedinstvenost u bazi pre izmene.
     */
    @Email(message = "Nevalidan format email-a")
    private String email;

    /** Novi broj telefona klijenta u internacionalnom formatu. */
    @Pattern(regexp = "^\\+?[0-9]{8,15}$", message = "Neispravan broj telefona")
    private String brojTelefona;

    /** Nova adresa stanovanja klijenta. */
    private String adresa;
}
