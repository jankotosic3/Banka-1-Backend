package com.banka1.exchangeService.exception;

import lombok.Getter;

/**
 * Domen-specifican runtime izuzetak koji nosi strukturiran {@link ErrorCode}.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
