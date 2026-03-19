package com.banka1.clientService.dto.responses;

import com.banka1.clientService.domain.enums.ClientRole;
import com.banka1.clientService.domain.enums.Pol;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO koji predstavlja podatke o klijentu koji se vracaju pozivaocu.
 * Ne sadrzi osetljive podatke poput lozinke, salta ili JMBG-a.
 */
@Getter
@Setter
@AllArgsConstructor
public class ClientResponseDto {

    /** Identifikator klijenta. */
    private Long id;

    /** Ime klijenta. */
    private String ime;

    /** Prezime klijenta. */
    private String prezime;

    /** Datum rodjenja kao Unix timestamp. */
    private Long datumRodjenja;

    /** Pol klijenta. */
    private Pol pol;

    /** Email adresa klijenta. */
    private String email;

    /** Broj telefona klijenta. */
    private String brojTelefona;

    /** Adresa stanovanja klijenta. */
    private String adresa;

    /** Uloga klijenta u sistemu. */
    private ClientRole role;
}
