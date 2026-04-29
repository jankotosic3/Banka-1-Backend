package com.banka1.order.advice;

import com.banka1.order.dto.ApiErrorResponse;
import com.banka1.order.exception.BadRequestException;
import com.banka1.order.exception.BusinessConflictException;
import com.banka1.order.exception.ForbiddenOperationException;
import com.banka1.order.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global REST exception handler for order-service.
 *
 * Provides centralized exception handling and maps exceptions to appropriate HTTP responses.
 * All REST endpoints automatically use this handler via Spring's @RestControllerAdvice.
 *
 * Exception Handling:
 * <ul>
 *   <li>ResourceNotFoundException → 404 Not Found</li>
 *   <li>ForbiddenOperationException → 403 Forbidden</li>
 *   <li>BadRequestException, IllegalArgumentException, MethodArgumentNotValidException → 400 Bad Request</li>
 *   <li>BusinessConflictException, IllegalStateException → 409 Conflict</li>
 *   <li>All other exceptions → 500 Internal Server Error</li>
 * </ul>
 *
 * Response Format:
 * All error responses are wrapped in ApiErrorResponse containing:
 * <ul>
 *   <li>timestamp: When the error occurred</li>
 *   <li>status: HTTP status code</li>
 *   <li>error: HTTP status reason phrase</li>
 *   <li>message: Exception message</li>
 *   <li>path: Request URI</li>
 *   <li>fieldErrors: Map of validation errors (for BAD_REQUEST)</li>
 * </ul>
 */
@RestControllerAdvice
public class OrderServiceExceptionHandler {

    /**
     * Handles ResourceNotFoundException (404 Not Found).
     * Thrown when a requested entity (order, portfolio, etc.) is not found.
     *
     * @param ex the ResourceNotFoundException
     * @param request the HTTP request
     * @return ResponseEntity with 404 status and error details
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /**
     * Handles ForbiddenOperationException (403 Forbidden).
     * Thrown when a user attempts an operation they don't have permission for.
     *
     * @param ex the ForbiddenOperationException
     * @param request the HTTP request
     * @return ResponseEntity with 403 status and error details
     */
    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleForbidden(ForbiddenOperationException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    /**
     * Handles AccessDeniedException (403 Forbidden).
     * Thrown by Spring Security when @PreAuthorize / method security rejects the caller's role.
     * Without this explicit handler the catch-all Exception handler maps it to 500.
     *
     * @param ex the AccessDeniedException
     * @param request the HTTP request
     * @return ResponseEntity with 403 status and error details
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "Access denied", request);
    }

    /**
     * Handles BadRequestException and validation errors (400 Bad Request).
     * Thrown when request data is invalid or violates business rules.
     * Includes detailed field-level validation errors when applicable.
     *
     * @param ex the exception (BadRequestException, IllegalArgumentException, or MethodArgumentNotValidException)
     * @param request the HTTP request
     * @return ResponseEntity with 400 status and error details
     */
    @ExceptionHandler({BadRequestException.class, IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        if (ex instanceof MethodArgumentNotValidException validationEx) {
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            for (FieldError fieldError : validationEx.getBindingResult().getFieldErrors()) {
                fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
            }
            return build(HttpStatus.BAD_REQUEST, "Request validation failed", request, fieldErrors);
        }
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * Handles BusinessConflictException and state errors (409 Conflict).
     * Thrown when an operation conflicts with the current business state or rules.
     *
     * @param ex the exception (BusinessConflictException or IllegalStateException)
     * @param request the HTTP request
     * @return ResponseEntity with 409 status and error details
     */
    @ExceptionHandler({BusinessConflictException.class, IllegalStateException.class})
    public ResponseEntity<ApiErrorResponse> handleConflict(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /**
     * Handles all other unexpected exceptions (500 Internal Server Error).
     * Logs unexpected errors and returns a generic error message for security.
     *
     * @param ex the unexpected exception
     * @param request the HTTP request
     * @return ResponseEntity with 500 status and generic error message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", request);
    }

    /**
     * Builds an ApiErrorResponse without field errors.
     *
     * @param status HTTP status code
     * @param message error message
     * @param request HTTP request
     * @return ResponseEntity with error response
     */
    private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String message, HttpServletRequest request) {
        return build(status, message, request, Map.of());
    }

    /**
     * Builds an ApiErrorResponse with optional field-level errors.
     *
     * @param status HTTP status code
     * @param message error message
     * @param request HTTP request
     * @param fieldErrors map of field-level validation errors
     * @return ResponseEntity with error response
     */
    private ResponseEntity<ApiErrorResponse> build(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> fieldErrors
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                fieldErrors
        ));
    }
}
