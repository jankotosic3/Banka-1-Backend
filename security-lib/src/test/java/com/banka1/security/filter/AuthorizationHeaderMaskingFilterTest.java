package com.banka1.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthorizationHeaderMaskingFilterTest {

    private final AuthorizationHeaderMaskingFilter filter = new AuthorizationHeaderMaskingFilter();

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void doFilterInternal_maskira_authorization_header_u_MDC() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getHeader("Authorization")).thenReturn("Bearer eyJ-tajni-token");
        // Simulira dock-style chain pristup MDC-u
        doAnswer(inv -> {
            assertThat(MDC.get("http.header.authorization")).isEqualTo("[MASKED]");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, resp, chain);

        // Cleanup posle (proveri da je MDC ocisten)
        assertThat(MDC.get("http.header.authorization")).isNull();
    }

    @Test
    void doFilterInternal_ne_dodaje_MDC_kada_header_ne_postoji() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(req, resp, chain);

        assertThat(MDC.get("http.header.authorization")).isNull();
    }
}
