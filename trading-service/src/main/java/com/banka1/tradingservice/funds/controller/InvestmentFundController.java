package com.banka1.tradingservice.funds.controller;

import com.banka1.tradingservice.funds.domain.ClientFundTransaction;
import com.banka1.tradingservice.funds.dto.ClientFundPositionDto;
import com.banka1.tradingservice.funds.dto.CreateFundRequest;
import com.banka1.tradingservice.funds.dto.FundHoldingDto;
import com.banka1.tradingservice.funds.dto.FundPerformancePointDto;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import com.banka1.tradingservice.funds.dto.InvestmentRequest;
import com.banka1.tradingservice.funds.dto.ReassignManagerRequest;
import com.banka1.tradingservice.funds.dto.RedemptionRequest;
import com.banka1.tradingservice.funds.service.FundLiquidationService;
import com.banka1.tradingservice.funds.service.InvestmentFundService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/funds")
@RequiredArgsConstructor
public class InvestmentFundController {

    private final InvestmentFundService fundService;
    private final FundLiquidationService fundLiquidationService;

    // -------------------- discovery --------------------

    @GetMapping
    public ResponseEntity<List<InvestmentFundDto>> discovery() {
        return ResponseEntity.ok(fundService.discovery());
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvestmentFundDto> details(@PathVariable Long id) {
        return ResponseEntity.ok(fundService.details(id));
    }

    // -------------------- supervisor fund mgmt --------------------

    @PostMapping
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<InvestmentFundDto> createFund(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid CreateFundRequest req) {
        Long managerId = jwt.getClaim("id");
        return new ResponseEntity<>(fundService.createFund(req, managerId), HttpStatus.CREATED);
    }

    @GetMapping("/supervised")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<List<InvestmentFundDto>> supervised(@AuthenticationPrincipal Jwt jwt) {
        Long managerId = jwt.getClaim("id");
        return ResponseEntity.ok(fundService.supervisedBy(managerId));
    }

    // -------------------- client invest/redeem --------------------

    @PostMapping("/{id}/invest")
    @PreAuthorize("hasRole('CLIENT_TRADING')")
    public ResponseEntity<ClientFundTransaction> invest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") Long fundId,
            @RequestBody @Valid InvestmentRequest req) {
        Long clientId = jwt.getClaim("id");
        return new ResponseEntity<>(fundService.invest(fundId, clientId, req), HttpStatus.ACCEPTED);
    }

    @PostMapping("/{id}/redeem")
    @PreAuthorize("hasRole('CLIENT_TRADING')")
    public ResponseEntity<ClientFundTransaction> redeem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") Long fundId,
            @RequestBody @Valid RedemptionRequest req) {
        Long clientId = jwt.getClaim("id");
        return new ResponseEntity<>(fundService.redeem(fundId, clientId, req), HttpStatus.ACCEPTED);
    }

    @GetMapping("/my-positions")
    @PreAuthorize("hasRole('CLIENT_TRADING')")
    public ResponseEntity<List<ClientFundPositionDto>> myPositions(@AuthenticationPrincipal Jwt jwt) {
        Long clientId = jwt.getClaim("id");
        return ResponseEntity.ok(fundService.myPositions(clientId));
    }

    // -------------------- bank invest/redeem (supervisor) --------------------

    @PostMapping("/{id}/bank-invest")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<ClientFundTransaction> bankInvest(
            @PathVariable("id") Long fundId,
            @RequestBody @Valid InvestmentRequest req) {
        return new ResponseEntity<>(fundService.bankInvest(fundId, req), HttpStatus.ACCEPTED);
    }

    @PostMapping("/{id}/bank-redeem")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<ClientFundTransaction> bankRedeem(
            @PathVariable("id") Long fundId,
            @RequestBody @Valid RedemptionRequest req) {
        return new ResponseEntity<>(fundService.bankRedeem(fundId, req), HttpStatus.ACCEPTED);
    }

    @GetMapping("/bank-positions")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<List<ClientFundPositionDto>> bankPositions() {
        return ResponseEntity.ok(fundService.bankPositions());
    }

    // -------------------- fund holdings --------------------

    @GetMapping("/{id}/securities")
    public ResponseEntity<List<FundHoldingDto>> securities(@PathVariable("id") Long fundId) {
        return ResponseEntity.ok(fundService.getEnrichedHoldings(fundId));
    }

    @PostMapping("/{id}/securities/{ticker}/sell")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<FundLiquidationService.SellResult> sellSecurity(
            @PathVariable("id") Long fundId,
            @PathVariable("ticker") String ticker,
            @RequestBody @Valid SellRequest req) {
        return ResponseEntity.ok(fundLiquidationService.sellHolding(fundId, ticker, req.getQuantity()));
    }

    @Data
    public static class SellRequest {
        @NotNull
        @Positive
        private Integer quantity;
    }

    // -------------------- fund positions (supervisor view) --------------------

    @GetMapping("/{id}/positions")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<List<ClientFundPositionDto>> fundPositions(@PathVariable("id") Long fundId) {
        return ResponseEntity.ok(fundService.fundPositions(fundId));
    }

    // -------------------- transaction history --------------------

    @GetMapping("/my-transactions")
    @PreAuthorize("hasRole('CLIENT_TRADING')")
    public ResponseEntity<List<ClientFundTransaction>> myTransactions(@AuthenticationPrincipal Jwt jwt) {
        Long clientId = jwt.getClaim("id");
        return ResponseEntity.ok(fundService.myTransactions(clientId));
    }

    @GetMapping("/{id}/transactions")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<List<ClientFundTransaction>> fundTransactions(@PathVariable("id") Long fundId) {
        return ResponseEntity.ok(fundService.fundTransactions(fundId));
    }

    @GetMapping("/{id}/performance")
    public ResponseEntity<List<FundPerformancePointDto>> fundPerformance(@PathVariable("id") Long fundId) {
        return ResponseEntity.ok(fundService.fundPerformance(fundId));
    }

    // -------------------- admin --------------------

    @PatchMapping("/admin/reassign-manager")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Void> reassignManager(@RequestBody @Valid ReassignManagerRequest req) {
        fundService.reassignManager(req.getOldManagerId(), req.getNewManagerId());
        return ResponseEntity.noContent().build();
    }
}
