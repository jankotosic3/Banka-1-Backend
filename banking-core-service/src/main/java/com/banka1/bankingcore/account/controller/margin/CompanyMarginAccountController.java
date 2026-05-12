package com.banka1.bankingcore.account.controller.margin;

import com.banka1.bankingcore.account.dto.margin.CreateCompanyMarginAccountDto;
import com.banka1.bankingcore.account.dto.margin.MarginAccountResponseDto;
import com.banka1.bankingcore.account.service.margin.MarginAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST kontroler za marzne racune kompanija (PR_03 C3.3).
 * Spec: Marzni_Racuni.txt
 */
@RestController
@RequestMapping("/accounts/company")
@RequiredArgsConstructor
public class CompanyMarginAccountController {

    private final MarginAccountService marginAccountService;

    /**
     * Spec: POST /accounts/company/createMarginAccount.
     */
    @PostMapping("/createMarginAccount")
    public ResponseEntity<MarginAccountResponseDto> createForCompany(
            @RequestBody @Valid CreateCompanyMarginAccountDto dto) {
        MarginAccountResponseDto response = marginAccountService.createForCompany(dto);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    /**
     * Spec: GET /accounts/company/getMarginCompany/{companyId}.
     */
    @GetMapping("/getMarginCompany/{companyId}")
    public ResponseEntity<MarginAccountResponseDto> getMarginCompany(@PathVariable Long companyId) {
        return ResponseEntity.ok(marginAccountService.findByCompanyId(companyId));
    }
}
