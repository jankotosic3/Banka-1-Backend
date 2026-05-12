package com.banka1.transaction_service.service;

import com.banka1.transaction_service.dto.request.PaymentRecipientRequestDto;
import com.banka1.transaction_service.dto.response.PaymentRecipientResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Servis za upravljanje primaocima placanja jednog klijenta.
 * Vlasnistvo se utvrdjuje iz JWT-a; svi zahtevi su automatski ograniceni
 * na primaoce trenutno prijavljenog klijenta.
 */
public interface PaymentRecipientService {

    Page<PaymentRecipientResponseDto> list(Jwt jwt, Pageable pageable);

    PaymentRecipientResponseDto create(Jwt jwt, PaymentRecipientRequestDto dto);

    PaymentRecipientResponseDto update(Jwt jwt, Long id, PaymentRecipientRequestDto dto);

    void delete(Jwt jwt, Long id);
}
