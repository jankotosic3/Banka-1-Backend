package com.banka1.order.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * DTO representing a bank-owned internal account reference resolved through employee/account APIs.
 */
@Data
public class BankAccountDto {

    /** Account reference returned by account-service; may be an internal id or account number. */
    @JsonAlias({"id", "accountId", "accountNumber", "brojRacuna"})
    private Long accountId;
}
