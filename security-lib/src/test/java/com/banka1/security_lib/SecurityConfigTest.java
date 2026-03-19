package com.banka1.security_lib;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void passwordEncoderBeanUsesArgon2Implementation() {
        PasswordEncoder encoder = securityConfig.passwordEncoder();

        assertThat(encoder.getClass().getSimpleName()).isEqualTo("Argon2PasswordEncoder");
    }

    @Test
    void roleHierarchyExpandsInheritedRoles() {
        var reachableAuthorities = securityConfig.roleHierarchy().getReachableGrantedAuthorities(
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        assertThat(reachableAuthorities)
                .extracting(authority -> authority.getAuthority())
                .contains("ROLE_ADMIN", "ROLE_SUPERVISOR", "ROLE_AGENT", "ROLE_BASIC");
    }
}
