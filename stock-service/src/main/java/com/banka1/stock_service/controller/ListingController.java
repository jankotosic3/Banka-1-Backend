package com.banka1.stock_service.controller;

import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.dto.ListingFilterRequest;
import com.banka1.stock_service.dto.ListingDetailsPeriod;
import com.banka1.stock_service.dto.ListingDetailsResponse;
import com.banka1.stock_service.dto.ListingRefreshResponse;
import com.banka1.stock_service.dto.ListingSortField;
import com.banka1.stock_service.dto.ListingSummaryResponse;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.service.ListingMarketDataRefreshService;
import com.banka1.stock_service.service.ListingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Listing API for listing-catalog queries and manual market-data refresh operations.
 */
@RestController
@RequiredArgsConstructor
public class ListingController {

    private final ListingMarketDataRefreshService listingMarketDataRefreshService;
    private final ListingQueryService listingQueryService;
    private final ListingRepository listingRepository;

    /**
     * Returns one detailed listing view with type-specific fields and historical prices.
     *
     * @param id listing identifier
     * @param period requested history window
     * @return detailed listing response
     */
    @GetMapping("/api/listings/{id}")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN', 'SERVICE')")
    public ResponseEntity<ListingDetailsResponse> getListingDetails(
            @PathVariable Long id,
            @RequestParam ListingDetailsPeriod period,
            Authentication authentication
    ) {
        // Guard: resolve listing type cheaply before triggering full DB work, so that
        // unauthorized forex access is rejected before any heavy query executes.
        ListingType listingType = listingRepository.findListingTypeById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Listing with id %s was not found.".formatted(id)));
        rejectUnauthorizedForexAccessByType(listingType, authentication);

        ListingDetailsResponse response = listingQueryService.getListingDetails(id, period);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns paginated stock listings available to clients and actuary-side users.
     *
     * @param filter shared listing filters
     * @param page zero-based page index
     * @param size page size
     * @param sortBy supported sort field
     * @param sortDirection sort direction
     * @return paginated stock listings
     */
    @GetMapping("/api/listings/stocks")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN', 'SERVICE')")
    public ResponseEntity<Page<ListingSummaryResponse>> getStockListings(
            @ModelAttribute ListingFilterRequest filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ticker") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDirection
    ) {
        Page<ListingSummaryResponse> response = listingQueryService.getStockListings(
                filter,
                page,
                size,
                ListingSortField.fromParameter(sortBy),
                resolveSortDirection(sortDirection)
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Returns paginated futures listings available to clients and actuary-side users.
     *
     * @param filter shared listing filters
     * @param page zero-based page index
     * @param size page size
     * @param sortBy supported sort field
     * @param sortDirection sort direction
     * @return paginated futures listings
     */
    @GetMapping("/api/listings/futures")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN', 'SERVICE')")
    public ResponseEntity<Page<ListingSummaryResponse>> getFuturesListings(
            @ModelAttribute ListingFilterRequest filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ticker") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDirection
    ) {
        Page<ListingSummaryResponse> response = listingQueryService.getFuturesListings(
                filter,
                page,
                size,
                ListingSortField.fromParameter(sortBy),
                resolveSortDirection(sortDirection)
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Returns paginated FX listings available only to actuary-side users.
     *
     * @param filter shared listing filters
     * @param page zero-based page index
     * @param size page size
     * @param sortBy supported sort field
     * @param sortDirection sort direction
     * @return paginated FX listings
     */
    @GetMapping("/api/listings/forex")
    @PreAuthorize("hasAnyRole('BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN', 'SERVICE')")
    public ResponseEntity<Page<ListingSummaryResponse>> getForexListings(
            @ModelAttribute ListingFilterRequest filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ticker") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDirection
    ) {
        Page<ListingSummaryResponse> response = listingQueryService.getForexListings(
                filter,
                page,
                size,
                ListingSortField.fromParameter(sortBy),
                resolveSortDirection(sortDirection)
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Manually refreshes one listing snapshot by id.
     *
     * @param id listing identifier
     * @return refresh summary
     */
    @PostMapping("/api/listings/{id}/refresh")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public ResponseEntity<ListingRefreshResponse> refreshListing(@PathVariable Long id) {
        ListingRefreshResponse response = listingMarketDataRefreshService.refreshListing(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Parses the sort-direction query parameter used by catalog endpoints.
     *
     * @param sortDirection raw query-parameter value
     * @return parsed Spring sort direction
     */
    private Sort.Direction resolveSortDirection(String sortDirection) {
        return Sort.Direction.fromOptionalString(sortDirection)
                .orElseThrow(() -> new ResponseStatusException(
                        BAD_REQUEST,
                        "Unsupported sortDirection value '%s'. Supported values are asc and desc."
                                .formatted(sortDirection)
                ));
    }

    /**
     * Keeps FX detail access aligned with the existing FX catalog authorization rules.
     * Called before the full DB fetch so that unauthorized callers are rejected cheaply.
     *
     * @param listingType listing type resolved from a lightweight repository query
     * @param authentication caller authentication
     */
    private void rejectUnauthorizedForexAccessByType(ListingType listingType, Authentication authentication) {
        if (listingType != ListingType.FOREX) {
            return;
        }

        boolean hasActuaryAccess = authentication.getAuthorities().stream()
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .anyMatch(authority -> authority.equals("ROLE_BASIC")
                        || authority.equals("ROLE_AGENT")
                        || authority.equals("ROLE_SUPERVISOR")
                        || authority.equals("ROLE_ADMIN")
                        || authority.equals("ROLE_SERVICE"));

        if (!hasActuaryAccess) {
            throw new ResponseStatusException(FORBIDDEN, "Forex listing details are not available to client users.");
        }
    }
}
