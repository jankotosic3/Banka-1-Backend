package com.banka1.clientService.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * DTO za zahtev autentifikacije klijenta.
 */
@Data
public class LoginRequestDto {

    /** Email adresa klijenta. */
    @Email(message = "Nevalidan format email-a")
    @NotBlank(message = "Email je obavezan")
    private String email;

    /** Lozinka klijenta u plain-text formatu (poredi se sa Argon2 hashom iz baze). */
    @NotBlank(message = "Lozinka je obavezna")
    private String password;
}
