package com.banka1.card_service.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Centralized catalog of business errors exposed by the card service.
 * Each constant defines the HTTP status returned to clients, a stable machine-readable error code,
 * and a short human-readable title.
 */
@Getter
public enum ErrorCode {

    /**
     * Returned when the caller provides a blank or missing account number.
     */
    INVALID_ACCOUNT_NUMBER(HttpStatus.BAD_REQUEST, "ERR_CARD_001", "Invalid account number"),

    /**
     * Returned when the caller provides a negative or missing card limit.
     */
    INVALID_CARD_LIMIT(HttpStatus.BAD_REQUEST, "ERR_CARD_002", "Invalid card limit"),

    /**
     * Returned when the service cannot generate a unique card number after repeated attempts.
     */
    CARD_NUMBER_GENERATION_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "ERR_CARD_003",
            "Card number generation failed"
    ),

    /**
     * Returned when no card exists for the given card number.
     */
    CARD_NOT_FOUND(HttpStatus.NOT_FOUND, "ERR_CARD_004", "Card not found"),

    /**
     * Returned when the requested status transition is not allowed by the state machine.
     */
    INVALID_STATUS_TRANSITION(HttpStatus.UNPROCESSABLE_ENTITY, "ERR_CARD_005", "Invalid status transition"),

    /**
     * Returned when a negative or null card limit is provided.
     */
    INVALID_LIMIT(HttpStatus.BAD_REQUEST, "ERR_CARD_006", "Invalid card limit"),

    /**
     * Returned when a client attempts to perform an action on a card they do not own.
     */
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ERR_CARD_007", "Access denied");

    /**
     * HTTP status mapped by the global exception handler.
     */
    private final HttpStatus httpStatus;

    /**
     * Stable machine-readable code intended for clients and integrations.
     */
    private final String code;

    /**
     * Short title suitable for API error responses.
     */
    private final String title;

    ErrorCode(HttpStatus httpStatus, String code, String title) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.title = title;
    }
}
