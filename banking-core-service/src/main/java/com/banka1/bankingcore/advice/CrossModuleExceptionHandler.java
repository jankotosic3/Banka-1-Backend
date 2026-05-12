package com.banka1.bankingcore.advice;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-module exception handler za konsolidovani banking-core-service.
 *
 * Svaki legacy modul (account, card, transaction, transfer, verification) ima
 * svoju zasebnu BusinessException klasu. Kada se exception iz jednog modula baci
 * u drugom (cross-module poziv), zasebni @ExceptionHandler-i ne hvataju ga jer
 * tip parametra ne odgovara, pa pada na generic Exception → 500.
 *
 * Resenje: hvata sve BusinessException-e kroz njihov base tip. Sve legacy
 * BusinessException klase ekstendiraju RuntimeException i imaju getter-e
 * errorCode/details. Reflection-om citamo HttpStatus iz ErrorCode enum-a.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CrossModuleExceptionHandler {

    @ExceptionHandler({
            com.banka1.account_service.exception.BusinessException.class,
            com.banka1.card_service.exception.BusinessException.class,
            com.banka1.transaction_service.exception.BusinessException.class,
            com.banka1.transfer.exception.BusinessException.class,
            com.banka1.verificationService.exception.BusinessException.class
    })
    public ResponseEntity<Map<String, Object>> handleAnyBusinessException(RuntimeException ex) {
        return mapBusinessException(ex);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", "ERR_NOT_FOUND");
        body.put("errorTitle", "Resurs nije pronađen");
        body.put("errorDesc", ex.getResourcePath());
        body.put("timestamp", LocalDateTime.now().toString());
        return new ResponseEntity<>(body, HttpStatus.NOT_FOUND);
    }

    /**
     * Vraca 400 Bad Request umesto 500 kada je JSON payload neispravan
     * (npr. tip parametra ne odgovara, ili je payload malformiran).
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", "ERR_VALIDATION");
        body.put("errorTitle", "Neispravni podaci");
        String detail = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        body.put("errorDesc", detail);
        body.put("timestamp", LocalDateTime.now().toString());
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    /**
     * Vraca 405 Method Not Allowed kada metod nije podrzan.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", "ERR_METHOD_NOT_ALLOWED");
        body.put("errorTitle", "Metod nije dozvoljen");
        body.put("errorDesc", ex.getMessage());
        body.put("timestamp", LocalDateTime.now().toString());
        return new ResponseEntity<>(body, HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Vraca 400 kada nedostaje obavezni request parameter.
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(org.springframework.web.bind.MissingServletRequestParameterException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", "ERR_VALIDATION");
        body.put("errorTitle", "Nedostaje parametar");
        body.put("errorDesc", ex.getMessage());
        body.put("timestamp", LocalDateTime.now().toString());
        return new ResponseEntity<>(body, HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<Map<String, Object>> mapBusinessException(RuntimeException ex) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        String code = "ERR_BUSINESS";
        String title = "Poslovna greška";
        try {
            Method getErrorCode = ex.getClass().getMethod("getErrorCode");
            Object errorCode = getErrorCode.invoke(ex);
            if (errorCode != null) {
                try {
                    Method getStatus = errorCode.getClass().getMethod("getHttpStatus");
                    Object statusObj = getStatus.invoke(errorCode);
                    if (statusObj instanceof HttpStatus s) status = s;
                } catch (NoSuchMethodException ignored) {}
                try {
                    Method getCode = errorCode.getClass().getMethod("getCode");
                    Object codeObj = getCode.invoke(errorCode);
                    if (codeObj != null) code = codeObj.toString();
                } catch (NoSuchMethodException ignored) {}
                try {
                    Method getTitle = errorCode.getClass().getMethod("getTitle");
                    Object titleObj = getTitle.invoke(errorCode);
                    if (titleObj != null) title = titleObj.toString();
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (ReflectiveOperationException ignored) {
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", code);
        body.put("errorTitle", title);
        body.put("errorDesc", ex.getMessage());
        body.put("timestamp", LocalDateTime.now().toString());
        return new ResponseEntity<>(body, status);
    }
}
