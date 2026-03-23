package com.banka1.exchangeService.advice;

import com.banka1.exchangeService.dto.ErrorResponseDto;
import com.banka1.exchangeService.exception.BusinessException;
import com.banka1.exchangeService.exception.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralizovan handler gresaka za REST controlled deo exchange-service-a.
 */
@RestControllerAdvice
@Component("exchangeServiceGlobalExceptionHandler")
public class GlobalExceptionHandler {

    /**
     * Mapira biznis izuzetke na odgovarajuci HTTP odgovor.
     *
     * @param ex domen-specifican izuzetak
     * @return standardizovan error payload
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponseDto> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        ErrorResponseDto error = new ErrorResponseDto(
                errorCode.getCode(),
                errorCode.getTitle(),
                ex.getMessage()
        );
        return new ResponseEntity<>(error, errorCode.getHttpStatus());
    }

    /**
     * Mapira DTO validacione greske na strukturisan odgovor.
     *
     * @param ex validacioni izuzetak
     * @return payload sa poljima koja nisu prosla validaciju
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()
                .body(buildValidationErrorResponse(ex.getBindingResult().getFieldErrors()));
    }

    /**
     * Mapira query/model bind validacione greske na strukturisan odgovor.
     *
     * @param ex validacioni izuzetak
     * @return payload sa poljima koja nisu prosla validaciju
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponseDto> handleBindException(BindException ex) {
        return ResponseEntity.badRequest()
                .body(buildValidationErrorResponse(ex.getBindingResult().getFieldErrors()));
    }

    private ErrorResponseDto buildValidationErrorResponse(Iterable<FieldError> fieldErrors) {
        Map<String, String> validationErrors = new HashMap<>();
        for (FieldError fieldError : fieldErrors) {
            validationErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        return new ErrorResponseDto(
                ErrorCode.VALIDATION_ERROR.getCode(),
                ErrorCode.VALIDATION_ERROR.getTitle(),
                "Molimo proverite unete podatke.",
                validationErrors
        );
    }

    /**
     * Hvata neocekivane greske i vraca genericki 500 odgovor.
     *
     * @param ex neocekivani izuzetak
     * @return genericki error payload
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpectedException(Exception ex) {
        return ResponseEntity.internalServerError()
                .body(new ErrorResponseDto(
                        "ERR_INTERNAL_SERVER",
                        "Serverska greska",
                        "Doslo je do neocekivane greske."
                ));
    }
}
