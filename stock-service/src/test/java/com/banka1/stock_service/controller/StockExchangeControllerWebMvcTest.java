package com.banka1.stock_service.controller;

import com.banka1.stock_service.dto.StockExchangeMarketPhase;
import com.banka1.stock_service.dto.StockExchangeResponse;
import com.banka1.stock_service.dto.StockExchangeStatusResponse;
import com.banka1.stock_service.dto.StockExchangeToggleResponse;
import com.banka1.stock_service.service.StockExchangeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockExchangeController.class)
@AutoConfigureMockMvc
@Import(StockExchangeControllerWebMvcTest.TestSecurityConfig.class)
@ActiveProfiles("test")
class StockExchangeControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockExchangeService stockExchangeService;

    @Test
    void getStockExchangesReturnsOkForAuthenticatedCaller() throws Exception {
        when(stockExchangeService.getStockExchanges()).thenReturn(List.of(
                new StockExchangeResponse(
                        1L,
                        "New York Stock Exchange",
                        "NYSE",
                        "XNYS",
                        "United States",
                        "USD",
                        "America/New_York",
                        LocalTime.of(9, 30),
                        LocalTime.of(16, 0),
                        LocalTime.of(7, 0),
                        LocalTime.of(9, 30),
                        LocalTime.of(16, 0),
                        LocalTime.of(20, 0),
                        true
                )
        ));

        mockMvc.perform(get("/api/stock-exchanges")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BASIC"))
                                .jwt(token -> token.claim("id", 10L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].exchangeMICCode").value("XNYS"))
                .andExpect(jsonPath("$[0].isActive").value(true));

        verify(stockExchangeService).getStockExchanges();
    }

    @Test
    void getStockExchangeStatusReturnsOkForAuthenticatedCaller() throws Exception {
        when(stockExchangeService.getStockExchangeStatus(1L)).thenReturn(new StockExchangeStatusResponse(
                1L,
                "New York Stock Exchange",
                "NYSE",
                "XNYS",
                "United States",
                "America/New_York",
                LocalDate.of(2026, 4, 6),
                LocalTime.of(10, 30),
                LocalTime.of(9, 30),
                LocalTime.of(16, 0),
                LocalTime.of(7, 0),
                LocalTime.of(9, 30),
                LocalTime.of(16, 0),
                LocalTime.of(20, 0),
                true,
                true,
                false,
                true,
                true,
                false,
                StockExchangeMarketPhase.REGULAR_MARKET
        ));

        mockMvc.perform(get("/api/stock-exchanges/1/is-open")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketPhase").value("REGULAR_MARKET"))
                .andExpect(jsonPath("$.open").value(true));

        verify(stockExchangeService).getStockExchangeStatus(1L);
    }

    @Test
    void toggleStockExchangeActiveReturnsForbiddenForBasicRole() throws Exception {
        mockMvc.perform(put("/api/stock-exchanges/1/toggle-active")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BASIC"))
                                .jwt(token -> token.claim("id", 11L))))
                .andExpect(status().isForbidden());
    }

    @Test
    void toggleStockExchangeActiveReturnsOkForSupervisorRole() throws Exception {
        when(stockExchangeService.toggleStockExchangeActive(1L))
                .thenReturn(new StockExchangeToggleResponse(1L, "New York Stock Exchange", "XNYS", false));

        mockMvc.perform(put("/api/stock-exchanges/1/toggle-active")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))
                                .jwt(token -> token.claim("id", 12L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));

        verify(stockExchangeService).toggleStockExchangeActive(1L);
    }

    @Test
    void toggleStockExchangeActiveReturnsNotFoundForUnknownId() throws Exception {
        when(stockExchangeService.toggleStockExchangeActive(290000L))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "Stock exchange with id 290000 was not found."));

        mockMvc.perform(put("/api/stock-exchanges/290000/toggle-active")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(token -> token.claim("id", 13L))))
                .andExpect(status().isNotFound());

        verify(stockExchangeService).toggleStockExchangeActive(290000L);
    }

    @TestConfiguration
    @EnableMethodSecurity
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                    .build();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "test-user")
                    .build();
        }
    }
}
