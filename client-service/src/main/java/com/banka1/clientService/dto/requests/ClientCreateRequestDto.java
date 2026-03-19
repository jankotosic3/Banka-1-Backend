package com.banka1.clientService.dto.requests;

import com.banka1.clientService.domain.enums.Pol;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * DTO za zahtev kreiranja novog klijenta.
 * Sva polja su obavezna osim {@code brojTelefona} i {@code adresa}.
 */
@Data
public class ClientCreateRequestDto {

    /** Ime klijenta. */
    @NotBlank(message = "Ime je obavezno")
    private String ime;

    /** Prezime klijenta. */
    @NotBlank(message = "Prezime je obavezno")
    private String prezime;

    /** Datum rodjenja klijenta kao Unix timestamp (milisekunde). */
    @NotNull(message = "Datum rodjenja je obavezan")
    private Long datumRodjenja;

    /** Pol klijenta. */
    @NotNull(message = "Pol je obavezan")
    private Pol pol;

    /** Email adresa klijenta. */
    @Email(message = "Nevalidan format email-a")
    @NotBlank(message = "Email je obavezan")
    private String email;

    /** Broj telefona klijenta (opcioni, u internacionalnom formatu). */
    @Pattern(
            regexp = "^\\+?[0-9]{8,15}$",
            message = "Neispravan broj telefona"
    )
    private String brojTelefona;

    /** Adresa stanovanja klijenta (opciona). */
    private String adresa;

    /** JMBG klijenta – jedinstveni identifikator, ne sme se menjati. */
    @NotBlank(message = "JMBG je obavezan")
    @Size(min = 13, max = 13, message = "JMBG mora imati tacno 13 cifara")
    @Pattern(regexp = "^[0-9]{13}$", message = "JMBG mora sadrzati samo cifre")
    private String jmbg;

}
