package com.banka1.order.dto;

import java.util.Set;

/**
 * Minimal authenticated-user context extracted from the incoming JWT.
 */
public record AuthenticatedUser(Long userId, Set<String> roles, Set<String> permissions) {

    public boolean hasRole(String role) {
        return roles.stream().anyMatch(current -> current.equalsIgnoreCase(role));
    }

    public boolean hasMarginPermission() {
        return permissions.stream().anyMatch(permission -> permission.toLowerCase().contains("margin"));
    }
}
