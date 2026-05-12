package com.banka1.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * PR_07 C7.3: log filter koji maskira osetljive header-e (Authorization, X-API-Key,
 * Cookie) pre nego sto stignu do MDC-ja ili access log-a.
 *
 * <p>Pre PR_07: ako je servis koristio
 * {@code logging.pattern.console=... %X{request.headers}} obrasac, JWT bi
 * bio logovan u plaintext-u — direktno krsenje GDPR-a.
 *
 * <p>Posle PR_07: filter zamenjuje vrednost svakog osetljivog header-a sa
 * {@code [MASKED]} pre nego sto je sledeci filter (request logger) procita.
 * MDC ulazi {@code http.authorization} i slicni se takode maskuju.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthorizationHeaderMaskingFilter extends OncePerRequestFilter {

    private static final String[] SENSITIVE_HEADERS = {
            "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie",
            "X-API-Key", "X-Auth-Token"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            for (String h : SENSITIVE_HEADERS) {
                if (request.getHeader(h) != null) {
                    MDC.put("http.header." + h.toLowerCase(), "[MASKED]");
                }
            }
            chain.doFilter(request, response);
        } finally {
            for (String h : SENSITIVE_HEADERS) {
                MDC.remove("http.header." + h.toLowerCase());
            }
        }
    }
}
