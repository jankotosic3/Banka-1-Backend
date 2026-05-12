package com.banka1.transaction_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Odgovor sa podacima primaoca placanja.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRecipientResponseDto {
    private Long id;
    private String naziv;
    private String brojRacuna;
}
