package com.banka1.stock_service.controller;

import com.banka1.stock_service.dto.ExchangeRuntimeStatusResponse;
import com.banka1.stock_service.dto.StockExchangeMarketPhase;
import com.banka1.stock_service.dto.StockExchangeResponse;
import com.banka1.stock_service.dto.StockExchangeStatusResponse;
import com.banka1.stock_service.dto.StockExchangeToggleResponse;
import com.banka1.stock_service.service.StockExchangeService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Stock exchange API for listing configured exchanges and checking their trading status.
 *
 * <p>The gateway owns the external service prefix.
 * This controller exposes the feature routes directly so the gateway can forward
 * them without duplicating another controller-level mapping.
 */
@RestController
@RequiredArgsConstructor
public class StockExchangeController {

    private final StockExchangeService stockExchangeService;

    /**
     * Returns every configured stock exchange.
     *
     * @return list of exchanges
     */
    @Operation(summary = "Get all stock exchanges")
    @GetMapping("/api/stock-exchanges")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN', 'SERVICE')")
    public ResponseEntity<List<StockExchangeResponse>> getStockExchanges() {
        List<StockExchangeResponse> response = stockExchangeService.getStockExchanges();
        return ResponseEntity.ok(response);
    }

    /**
     * Returns the live trading status of one stock exchange using its local timezone.
     *
     * @param id exchange identifier
     * @return runtime market-status response
     */
    @Operation(summary = "Get stock exchange open status")
    @GetMapping("/api/stock-exchanges/{id}/is-open")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN', 'SERVICE')")
    public ResponseEntity<StockExchangeStatusResponse> getStockExchangeStatus(@PathVariable Long id) {
        StockExchangeStatusResponse response = stockExchangeService.getStockExchangeStatus(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Compact runtime status for service-to-service callers (e.g. order-service).
     * Mirrors {@code /is-open} but returns only open / closed / after-hours flags.
     *
     * @param id exchange identifier
     * @return runtime flags suitable for trading-window decisions
     */
    @Operation(summary = "Get compact stock exchange runtime status")
    @GetMapping("/api/stock-exchanges/{id}/status")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN', 'SERVICE')")
    public ResponseEntity<ExchangeRuntimeStatusResponse> getStockExchangeRuntimeStatus(@PathVariable Long id) {
        StockExchangeStatusResponse status = stockExchangeService.getStockExchangeStatus(id);
        boolean afterHours = status.marketPhase() == StockExchangeMarketPhase.POST_MARKET;
        return ResponseEntity.ok(new ExchangeRuntimeStatusResponse(status.open(), afterHours, !status.open()));
    }

    /**
     * Flips the testing flag that bypasses open-hour validation for one exchange.
     *
     * @param id exchange identifier
     * @return updated toggle state
     */
    @Operation(summary = "Toggle stock exchange active flag")
    @PutMapping("/api/stock-exchanges/{id}/toggle-active")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public ResponseEntity<StockExchangeToggleResponse> toggleStockExchangeActive(@PathVariable Long id) {
        try {
            StockExchangeToggleResponse response = stockExchangeService.toggleStockExchangeActive(id);
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return ResponseEntity.notFound().build();
            }
            throw exception;
        }
    }
}
