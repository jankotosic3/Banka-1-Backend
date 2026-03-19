package com.banka1.clientService.dto.rabbitmq;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO koji nosi link za reset lozinke ili aktivaciju naloga unutar {@link EmailDto}.
 * Tacno jedno od polja ({@code resetLink} ili {@code activationLink}) bice popunjeno
 * u zavisnosti od tipa mejla. Polja sa {@code null} vrednoscu se preskaciju pri serijalizaciji.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@Getter
@Setter
public class ResetLinkDto {

    /**
     * Link za reset lozinke (popunjava se samo za {@link EmailType#CLIENT_PASSWORD_RESET}).
     */
    private String resetLink;

    /**
     * Link za aktivaciju naloga (popunjava se samo za {@link EmailType#CLIENT_CREATED}).
     */
    private String activationLink;

    /**
     * Popunjava odgovarajuce polje za link na osnovu tipa email poruke.
     *
     * @param link      link za reset lozinke ili aktivaciju naloga
     * @param emailType tip mejla koji odredjuje koje polje se popunjava
     */
    public ResetLinkDto(String link, EmailType emailType) {
        switch (emailType) {
            case CLIENT_PASSWORD_RESET -> resetLink = link;
            case CLIENT_CREATED -> activationLink = link;
            default -> throw new IllegalStateException("Unsupported EmailType: " + emailType);
        }
    }
}
