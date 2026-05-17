package com.banka1.tradingservice.funds.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReassignManagerRequest {
    @NotNull
    private Long oldManagerId;
    @NotNull
    private Long newManagerId;
}