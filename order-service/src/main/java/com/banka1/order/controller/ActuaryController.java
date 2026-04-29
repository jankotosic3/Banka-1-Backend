package com.banka1.order.controller;

import com.banka1.order.dto.ActuaryAgentDto;
import com.banka1.order.dto.SetLimitRequestDto;
import com.banka1.order.dto.SetNeedApprovalRequestDto;
import com.banka1.order.dto.SimpleResponse;
import com.banka1.order.service.ActuaryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller exposing actuary (agent/supervisor) management endpoints.
 *
 * Provides supervisor-only access to agent trading limit management and status.
 * Combines employee data from employee-service with local ActuaryInfo records
 * to display comprehensive actuary information.
 *
 * All endpoints require the SUPERVISOR role (which includes ADMIN via role hierarchy).
 *
 * Endpoints:
 * <ul>
 *   <li>GET /actuaries/agents - List all agents with optional filtering</li>
 *   <li>PUT /actuaries/agents/{id}/limit - Update agent daily trading limit</li>
 *   <li>PUT /actuaries/agents/{id}/reset-limit - Reset agent's daily used limit</li>
 *   <li>PUT /actuaries/agents/{id}/need-approval - Toggle agent's need-approval flag</li>
 * </ul>
 */
@RestController
@RequestMapping("/actuaries")
@RequiredArgsConstructor
public class ActuaryController {

    private final ActuaryService actuaryService;

    /**
     * Returns a paginated list of all agents, optionally filtered by employee data.
     *
     * Employee data is fetched from employee-service; actuary trading limits are
     * loaded from the local database and merged.
     *
     * @param email    optional email filter (partial match, case-insensitive)
     * @param ime      optional first name filter (partial match, case-insensitive)
     * @param prezime  optional last name filter (partial match, case-insensitive)
     * @param pozicija optional position filter (partial match, case-insensitive)
     * @param page     page index (default: 0)
     * @param size     page size (default: 10, max: 100)
     * @return paginated list of agents with their actuary limit information
     */
    @GetMapping("/agents")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<Page<ActuaryAgentDto>> getAgents(
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String ime,
            @RequestParam(required = false) String prezime,
            @RequestParam(required = false) String pozicija,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(actuaryService.getAgents(email, ime, prezime, pozicija, PageRequest.of(page, size)));
    }

    /**
     * Updates the daily trading limit for the specified agent.
     *
     * Only employees with the AGENT role can have their limit updated.
     * Supervisors cannot be targeted (supervisors have no daily limit).
     *
     * When a new limit is set, the agent's used limit is reset to zero.
     *
     * @param id      the employee ID of the agent
     * @param request request body containing the new limit value in RSD
     * @return 200 OK on success
     * @throws ResourceNotFoundException if employee not found
     */
    @PutMapping("/agents/{id}/limit")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<SimpleResponse> setLimit(
            @PathVariable Long id,
            @RequestBody @Valid SetLimitRequestDto request
    ) {
        actuaryService.setLimit(id, request);
        return ResponseEntity.ok(SimpleResponse.success("Limit updated successfully"));
    }

    /**
     * Resets the used daily limit ({@code usedLimit}) to zero for the specified agent.
     *
     * This allows supervisors to manually reset an agent's daily consumption at any time,
     * not just at the scheduled 23:59 reset. Useful for special circumstances or corrections.
     *
     * Only employees with the AGENT role can have their limit reset; supervisors and admins
     * have no daily limit and are rejected with 400 Bad Request.
     *
     * @param id the employee ID of the agent
     * @return 200 OK on success
     * @throws ResourceNotFoundException if employee not found
     */
    @PutMapping("/agents/{id}/reset-limit")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<SimpleResponse> resetLimit(@PathVariable Long id) {
        actuaryService.resetLimit(id);
        return ResponseEntity.ok(SimpleResponse.success("Limit reset successfully"));
    }

    /**
     * Toggles the {@code needApproval} flag for the specified agent.
     *
     * When enabled, every order placed by the agent lands in the PENDING queue
     * and requires explicit Approve/Decline by a supervisor, regardless of
     * whether it fits within the agent's daily limit. Disabling the flag
     * restores the normal flow where only over-limit or limit-exhausted orders
     * need approval.
     *
     * Only employees with the AGENT role can have the flag toggled; supervisors
     * (and admins) always operate without approval.
     *
     * @param id      the employee ID of the agent
     * @param request request body containing the new {@code needApproval} value
     * @return 200 OK on success
     */
    @PutMapping("/agents/{id}/need-approval")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<SimpleResponse> setNeedApproval(
            @PathVariable Long id,
            @RequestBody @Valid SetNeedApprovalRequestDto request
    ) {
        actuaryService.setNeedApproval(id, request);
        return ResponseEntity.ok(SimpleResponse.success("Need-approval flag updated successfully"));
    }
}
