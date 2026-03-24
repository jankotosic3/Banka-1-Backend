package com.banka1.card_service.advice;

import com.banka1.card_service.dto.ErrorResponseDto;
import com.banka1.card_service.exception.BusinessException;
import com.banka1.card_service.exception.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Centralized REST exception mapping for the card service.
 */
@RestControllerAdvice
@Component("cardServiceGlobalExceptionHandler")
public class GlobalExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponseDto> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        ErrorResponseDto error = new ErrorResponseDto(
                "ERR_CONSTRAINT_VIOLATION",
                "Constraint violation",
                "One of the provided values is already in use."
        );
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponseDto> handleNoSuchElement(NoSuchElementException ex) {
        ErrorResponseDto error = new ErrorResponseDto(
                "ERR_NOT_FOUND",
                "Resource not found",
                ex.getMessage()
        );
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponseDto> handleIllegalArgument(IllegalArgumentException ex) {
        ErrorResponseDto error = new ErrorResponseDto(
                "ERR_VALIDATION",
                "Invalid arguments",
                ex.getMessage()
        );
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> validationErrors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            validationErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ErrorResponseDto error = new ErrorResponseDto(
                "ERR_VALIDATION",
                "Invalid request body",
                "Please review the submitted fields.",
                validationErrors
        );
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpectedException(Exception ex) {
        ErrorResponseDto error = new ErrorResponseDto(
                "ERR_INTERNAL_SERVER",
                "Internal server error",
                "An unexpected error occurred."
        );
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
