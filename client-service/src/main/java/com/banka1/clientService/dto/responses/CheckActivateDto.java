package com.banka1.clientService.dto.responses;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO koji vraca identifikator confirmation tokena nakon uspesne validacije.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CheckActivateDto {

    /** Identifikator {@code ClientConfirmationToken} entiteta. */
    private Long id;
}
