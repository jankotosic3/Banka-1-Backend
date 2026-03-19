package com.banka1.security_lib;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityAutoConfigurationTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void corsConfigurationAllowsFrontendOrigin() {
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        var configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("http://localhost:4200");
        assertThat(configuration.getAllowedMethods()).containsExactly("*");
        assertThat(configuration.getAllowedHeaders()).containsExactly("*");
        assertThat(configuration.getAllowCredentials()).isTrue();
    }
}
