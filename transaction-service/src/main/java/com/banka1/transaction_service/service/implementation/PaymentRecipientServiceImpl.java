package com.banka1.transaction_service.service.implementation;

import com.banka1.transaction_service.domain.PaymentRecipient;
import com.banka1.transaction_service.dto.request.PaymentRecipientRequestDto;
import com.banka1.transaction_service.dto.response.PaymentRecipientResponseDto;
import com.banka1.transaction_service.exception.BusinessException;
import com.banka1.transaction_service.exception.ErrorCode;
import com.banka1.transaction_service.repository.PaymentRecipientRepository;
import com.banka1.transaction_service.service.PaymentRecipientService;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementacija {@link PaymentRecipientService}.
 * Sve operacije ekstrahuju identifikator vlasnika iz JWT claim-a {@code id} i
 * automatski filtriraju primaoce po tom vlasniku - klijent ne moze da vidi
 * niti menja primaoce drugih klijenata.
 */
@Service
@AllArgsConstructor
@Transactional
public class PaymentRecipientServiceImpl implements PaymentRecipientService {

    private final PaymentRecipientRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentRecipientResponseDto> list(Jwt jwt, Pageable pageable) {
        Long ownerId = extractOwnerId(jwt);
        return repository.findByOwnerClientId(ownerId, pageable).map(this::toDto);
    }

    @Override
    public PaymentRecipientResponseDto create(Jwt jwt, PaymentRecipientRequestDto dto) {
        Long ownerId = extractOwnerId(jwt);
        if (repository.existsByOwnerClientIdAndNaziv(ownerId, dto.getNaziv()))
            throw new BusinessException(ErrorCode.RECIPIENT_NAME_TAKEN, "Naziv: " + dto.getNaziv());
        PaymentRecipient entity = new PaymentRecipient();
        entity.setOwnerClientId(ownerId);
        entity.setNaziv(dto.getNaziv());
        entity.setBrojRacuna(dto.getBrojRacuna());
        return toDto(repository.save(entity));
    }

    @Override
    public PaymentRecipientResponseDto update(Jwt jwt, Long id, PaymentRecipientRequestDto dto) {
        Long ownerId = extractOwnerId(jwt);
        PaymentRecipient existing = repository.findByIdAndOwnerClientId(id, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECIPIENT_NOT_FOUND, "ID: " + id));

        // Ako se menja naziv, validiramo unique-per-owner
        if (!existing.getNaziv().equals(dto.getNaziv())
                && repository.existsByOwnerClientIdAndNaziv(ownerId, dto.getNaziv()))
            throw new BusinessException(ErrorCode.RECIPIENT_NAME_TAKEN, "Naziv: " + dto.getNaziv());

        existing.setNaziv(dto.getNaziv());
        existing.setBrojRacuna(dto.getBrojRacuna());
        return toDto(repository.save(existing));
    }

    @Override
    public void delete(Jwt jwt, Long id) {
        Long ownerId = extractOwnerId(jwt);
        PaymentRecipient existing = repository.findByIdAndOwnerClientId(id, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECIPIENT_NOT_FOUND, "ID: " + id));
        repository.delete(existing); // soft-delete kroz @SQLDelete
    }

    private Long extractOwnerId(Jwt jwt) {
        Object idClaim = jwt.getClaim("id");
        if (idClaim == null) throw new BusinessException(ErrorCode.INVALID_TOKEN, "JWT ne sadrzi id claim");
        return ((Number) idClaim).longValue();
    }

    private PaymentRecipientResponseDto toDto(PaymentRecipient entity) {
        return new PaymentRecipientResponseDto(entity.getId(), entity.getNaziv(), entity.getBrojRacuna());
    }
}
