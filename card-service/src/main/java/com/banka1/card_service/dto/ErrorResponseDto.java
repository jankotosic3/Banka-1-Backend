package com.banka1.card_service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Standard API error payload returned by the card service.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponseDto {

    private String errorCode;
    private String errorTitle;
    private String errorDesc;
    private LocalDateTime timestamp = LocalDateTime.now();
    private Map<String, String> validationErrors;

    public ErrorResponseDto(String errorCode, String errorTitle, String errorDesc) {
        this.errorCode = errorCode;
        this.errorTitle = errorTitle;
        this.errorDesc = errorDesc;
    }

    public ErrorResponseDto(
            String errorCode,
            String errorTitle,
            String errorDesc,
            Map<String, String> validationErrors
    ) {
        this.errorCode = errorCode;
        this.errorTitle = errorTitle;
        this.errorDesc = errorDesc;
        this.validationErrors = validationErrors;
    }
}
