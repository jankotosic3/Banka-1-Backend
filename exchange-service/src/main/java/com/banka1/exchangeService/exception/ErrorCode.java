package com.banka1.exchangeService.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Standardizovani kodovi gresaka za exchange-service.
 */
@Getter
public enum ErrorCode {
    EXCHANGE_RATE_NOT_FOUND(HttpStatus.NOT_FOUND, "ERR_EXCHANGE_RATE_NOT_FOUND", "Kurs nije pronadjen"),
    EXCHANGE_RATE_FETCH_FAILED(HttpStatus.BAD_GATEWAY, "ERR_EXCHANGE_RATE_FETCH_FAILED", "Neuspesan fetch kurseva"),
    UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST, "ERR_UNSUPPORTED_CURRENCY", "Nepodrzana valuta"),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "ERR_VALIDATION", "Neispravni podaci");

    private final HttpStatus httpStatus;
    private final String code;
    private final String title;

    ErrorCode(HttpStatus httpStatus, String code, String title) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.title = title;
    }
}
