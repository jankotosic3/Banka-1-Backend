package com.banka1.bankingcore.account.controller.margin;

import com.banka1.bankingcore.account.dto.margin.CreateUserMarginAccountDto;
import com.banka1.bankingcore.account.dto.margin.MarginAccountResponseDto;
import com.banka1.bankingcore.account.service.margin.MarginAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST kontroler za marzne racune (PR_03 C3.3).
 *
 * <p>Spec: Marzni_Racuni.txt — endpoint imena se cuvaju iz spec-a doslovno (camelCase
 * unutar URL path-a), iako Banka standardna konvencija koristi kebab-case. Razlog je
 * da frontend forme koje su vec napravljene prema spec-u ne moraju da menjaju URL.
 */
@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class MarginAccountController {

    private final MarginAccountService marginAccountService;

    /**
     * Kreira marzni racun za fizicko lice. Spec: POST /accounts/createMarginAccount.
     */
    @PostMapping("/createMarginAccount")
    public ResponseEntity<MarginAccountResponseDto> createForUser(
            @RequestBody @Valid CreateUserMarginAccountDto dto) {
        MarginAccountResponseDto response = marginAccountService.createForUser(dto);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    /**
     * Vraca marzni racun za korisnika. Spec: GET /accounts/getMarginUser/{userId}.
     */
    @GetMapping("/getMarginUser/{userId}")
    public ResponseEntity<MarginAccountResponseDto> getMarginUser(@PathVariable Long userId) {
        return ResponseEntity.ok(marginAccountService.findByUserId(userId));
    }
}
