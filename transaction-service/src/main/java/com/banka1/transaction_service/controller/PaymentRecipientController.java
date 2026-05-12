package com.banka1.transaction_service.controller;

import com.banka1.transaction_service.dto.request.PaymentRecipientRequestDto;
import com.banka1.transaction_service.dto.response.PaymentRecipientResponseDto;
import com.banka1.transaction_service.service.PaymentRecipientService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * REST kontroler za upravljanje primaocima placanja prijavljenog klijenta.
 * Spec: Celina 2 ("Primaoci placanja") - klijent moze da pregleda, kreira, izmeni i brise.
 * Vlasnik se identifikuje iz JWT-a; nema potrebe slati ownerClientId u body-ju.
 */
@RestController
@RequestMapping("/payment-recipients")
@AllArgsConstructor
@PreAuthorize("hasRole('CLIENT_BASIC')")
public class PaymentRecipientController {

    private final PaymentRecipientService paymentRecipientService;

    @Operation(summary = "Lista primaoca placanja prijavljenog klijenta")
    @GetMapping
    public ResponseEntity<Page<PaymentRecipientResponseDto>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(paymentRecipientService.list(jwt, PageRequest.of(page, size)));
    }

    @Operation(summary = "Kreiranje novog primaoca placanja")
    @PostMapping
    public ResponseEntity<PaymentRecipientResponseDto> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid PaymentRecipientRequestDto dto) {
        return new ResponseEntity<>(paymentRecipientService.create(jwt, dto), HttpStatus.CREATED);
    }

    @Operation(summary = "Izmena postojeceg primaoca")
    @PutMapping("/{id}")
    public ResponseEntity<PaymentRecipientResponseDto> update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestBody @Valid PaymentRecipientRequestDto dto) {
        return ResponseEntity.ok(paymentRecipientService.update(jwt, id, dto));
    }

    @Operation(summary = "Brisanje primaoca (soft delete)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        paymentRecipientService.delete(jwt, id);
        return ResponseEntity.noContent().build();
    }
}
