package com.banka1.tradingservice.funds.dto;

import com.banka1.tradingservice.funds.domain.ClientFundTransactionStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class FundPerformancePointDto {
    private LocalDateTime timestamp;
    private Long transactionId;
    private BigDecimal amount;
    private Boolean inflow;
    private ClientFundTransactionStatus status;
    private BigDecimal totalValue;
    private BigDecimal profit;
}
