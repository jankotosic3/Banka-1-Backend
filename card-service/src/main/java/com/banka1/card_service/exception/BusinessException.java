package com.banka1.card_service.exception;

import lombok.Getter;

/**
 * Exception used for expected business failures in the card service.
 * Unlike unexpected technical exceptions, this exception always carries a structured {@link ErrorCode}
 * so the global exception handler can translate it into a stable HTTP response.
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * Structured error metadata associated with this exception.
     */
    private final ErrorCode errorCode;

    /**
     * Creates a new business exception with a detailed message.
     *
     * @param errorCode structured service error code
     * @param message detailed description intended for logs and API responses
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
