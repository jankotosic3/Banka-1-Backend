package com.banka1.security_lib;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Globalni handler za {@link NoResourceFoundException} koji se baca kada
 * Spring Boot 4 ne nađe rutu i pre nego što generic Exception handler
 * legacy modul-a vrati 500.
 *
 * <p>Auto-configuration ga aktivira u svim servisima koji koriste security-lib.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@AutoConfiguration
public class GlobalNoResourceHandler {

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", "ERR_NOT_FOUND");
        body.put("errorTitle", "Resurs nije pronađen");
        body.put("errorDesc", ex.getResourcePath());
        body.put("timestamp", LocalDateTime.now().toString());
        return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
    }
}
