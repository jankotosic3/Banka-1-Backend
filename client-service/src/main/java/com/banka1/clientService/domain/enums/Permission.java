package com.banka1.clientService.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;


/**Dodajem samo ovu permisiju zato sto se ostale svakako nece koristiti
 */

@Getter
@AllArgsConstructor
public enum Permission {

    MARGIN_TRADE("trgovina sa marginom");

    /** Citljiv opis permisije na srpskom jeziku. */
    private final String description;
}
