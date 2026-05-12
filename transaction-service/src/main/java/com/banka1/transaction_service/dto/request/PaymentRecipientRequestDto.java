package com.banka1.transaction_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Zahtev za kreiranje ili izmenu primaoca placanja.
 * Vlasnik (klijent) se uzima iz JWT-a a ne iz tela zahteva.
 */
@Data
public class PaymentRecipientRequestDto {

    @NotBlank(message = "Naziv je obavezan")
    @Size(max = 100, message = "Naziv ne sme biti duzi od 100 znakova")
    private String naziv;

    @NotBlank(message = "Broj racuna je obavezan")
    @Size(max = 50, message = "Broj racuna ne sme biti duzi od 50 znakova")
    private String brojRacuna;
}
