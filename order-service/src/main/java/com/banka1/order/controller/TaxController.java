package com.banka1.order.controller;

import com.banka1.order.service.TaxService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for capital gains tax operations.
 *
 * Exposes supervisor-facing endpoints for tax management and reporting.
 * Handles:
 * <ul>
 *   <li>Manual triggering of monthly capital gains tax calculation</li>
 *   <li>Querying user tax debts (aggregated in RSD)</li>
 *   <li>Viewing tax tracking and payment history</li>
 * </ul>
 *
 * Tax Calculation Rules:
 * <ul>
 *   <li>Tax rate: 15% of capital gain</li>
 *   <li>Capital gain: selling price - cost basis (from matched buy transaction)</li>
 *   <li>Only positive gains are taxed (losses have no tax implications)</li>
 *   <li>All amounts converted to RSD before transfer to state account</li>
 * </ul>
 *
 * Endpoint Categories:
 * <ul>
 *   <li><b>/tax/...</b> - Public API for supervisor portal</li>
 *   <li><b>/internal/tax/...</b> - Internal API for system-to-system communication</li>
 * </ul>
 */
@RestController
@RequestMapping
public class TaxController {

    private final TaxService taxService;

    public TaxController(TaxService taxService) {
        this.taxService = taxService;
    }

    /**
     * Manually triggers the monthly capital gains tax collection and settlement.
     *
     * Normally runs automatically via TaxScheduler at midnight on the first of each month.
     * This endpoint allows supervisors to trigger the process manually when needed.
     *
     * @return 200 OK if tax collection completed successfully
     */
    @PostMapping("/tax/collect")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<Void> collectTax() {
        taxService.collectMonthlyTaxManually();
        return ResponseEntity.ok().build();
    }

    /**
     * Triggers capital gains tax collection for all users from the first day of the current
     * calendar month up to the moment it is called.
     *
     * Useful for mid-month collection. Already-charged entries are skipped (idempotent).
     *
     * @return 200 OK if collection completed successfully
     */
    @PostMapping("/tax/collect/current-month")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<Void> collectCurrentMonthTax() {
        taxService.collectCurrentMonthTax();
        return ResponseEntity.ok().build();
    }

    /**
     * Internal endpoint for triggering capital gains tax calculation by system services.
     *
     * Used by other backend services or for internal orchestration when tax collection
     * needs to be triggered outside the normal schedule.
     * Secured with the inter-service SERVICE role used by order-service JWT.
     *
     * @return 200 OK if successfully triggered
     */
    @PostMapping("/internal/tax/capital-gains/run")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Void> runTaxCalculationInternal() {
        taxService.collectMonthlyTax();
        return ResponseEntity.ok().build();
    }

    /**
     * Retrieves a paginated list of all users with their current tax debts.
     *
     * Debt is calculated based on the latest tax computation and returned in RSD.
     * If users have accounts in multiple currencies, values are converted using
     * exchange-service (without commission).
     *
     * @param page page index (default: 0)
     * @param size page size (default: 10, max: 100)
     * @return paginated list of user tax debts in RSD
     */
    @GetMapping("/tax/capital-gains/debts")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<Page<com.banka1.order.dto.TaxDebtResponse>> getAllDebts(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(taxService.getAllDebts(PageRequest.of(page, size)));
    }

    /**
     * Retrieves the total tax debt for a specific user.
     *
     * @param userId ID of the user (client or agent)
     * @return tax debt in RSD, including both charged and pending amounts
     */
    @GetMapping("/tax/capital-gains/{userId}")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<com.banka1.order.dto.TaxDebtResponse> getUserDebt(@PathVariable Long userId) {
        return ResponseEntity.ok(taxService.getUserDebt(userId));
    }

    /**
     * Retrieves tax tracking rows for reporting with optional filtering.
     *
     * Provides a supervisor-facing tax tracking report with detailed information
     * about user tax obligations, payment status, and other relevant metrics.
     *
     * Filters (all optional, all case-insensitive):
     * <ul>
     *   <li>userType: "CLIENT" or "ACTUARY" (employee with trading authority)</li>
     *   <li>firstName: User's first name (partial match)</li>
     *   <li>lastName: User's last name (partial match)</li>
     * </ul>
     *
     * @param userType optional filter by user type (CLIENT or ACTUARY)
     * @param firstName optional filter by first name
     * @param lastName optional filter by last name
     * @param page page index (default: 0)
     * @param size page size (default: 10, max: 100)
     * @return paginated tax tracking information
     */
    @GetMapping("/tax/tracking")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<Page<com.banka1.order.dto.TaxTrackingRowResponse>> getTaxTracking(
            @RequestParam(required = false) String userType,
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(taxService.getTaxTracking(userType, firstName, lastName, PageRequest.of(page, size)));
    }
}
