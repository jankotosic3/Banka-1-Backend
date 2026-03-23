package com.banka1.exchangeService.dto;

import java.util.Map;

/**
 * Standardizovan format greske za REST API.
 *
 * @param code             aplikativni kod greske
 * @param title            kratki naslov greske
 * @param message          detaljna poruka greske
 * @param validationErrors opcione greske validacije po poljima
 */
public record ErrorResponseDto(
        String code,
        String title,
        String message,
        Map<String, String> validationErrors
) {

    public ErrorResponseDto(String code, String title, String message) {
        this(code, title, message, Map.of());
    }
}
