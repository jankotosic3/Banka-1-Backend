package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.dto.StockExchangeMarketPhase;
import com.banka1.stock_service.dto.StockExchangeResponse;
import com.banka1.stock_service.dto.StockExchangeStatusResponse;
import com.banka1.stock_service.dto.StockExchangeToggleResponse;
import com.banka1.stock_service.repository.StockExchangeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StockExchangeServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class StockExchangeServiceImplTest {

    @Mock
    private StockExchangeRepository stockExchangeRepository;

    @Test
    void getStockExchangesReturnsMappedResponses() {
        when(stockExchangeRepository.findAllByOrderByExchangeNameAsc()).thenReturn(List.of(
                createExchange(2L, "NASDAQ Stock Market", "NASDAQ", "XNAS", true),
                createExchange(1L, "New York Stock Exchange", "NYSE", "XNYS", true)
        ));

        List<StockExchangeResponse> response = serviceAt("2026-04-06T14:30:00Z").getStockExchanges();

        assertThat(response).hasSize(2);
        assertThat(response.getFirst().exchangeName()).isEqualTo("NASDAQ Stock Market");
        assertThat(response.getFirst().exchangeMICCode()).isEqualTo("XNAS");
        assertThat(response.getFirst().isActive()).isTrue();
    }

    @Test
    void getStockExchangeStatusDetectsPreMarketFromExchangeTimezone() {
        when(stockExchangeRepository.findById(1L))
                .thenReturn(Optional.of(createExchange(1L, "New York Stock Exchange", "NYSE", "XNYS", true)));

        StockExchangeStatusResponse response = serviceAt("2026-04-06T13:00:00Z").getStockExchangeStatus(1L);

        assertThat(response.localDate()).isEqualTo(LocalDate.of(2026, 4, 6));
        assertThat(response.localTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(response.workingDay()).isTrue();
        assertThat(response.holiday()).isFalse();
        assertThat(response.open()).isTrue();
        assertThat(response.regularMarketOpen()).isFalse();
        assertThat(response.marketPhase()).isEqualTo(StockExchangeMarketPhase.PRE_MARKET);
    }

    @Test
    void getStockExchangeStatusDetectsRegularMarketHours() {
        when(stockExchangeRepository.findById(1L))
                .thenReturn(Optional.of(createExchange(1L, "New York Stock Exchange", "NYSE", "XNYS", true)));

        StockExchangeStatusResponse response = serviceAt("2026-04-06T14:30:00Z").getStockExchangeStatus(1L);

        assertThat(response.localTime()).isEqualTo(LocalTime.of(10, 30));
        assertThat(response.open()).isTrue();
        assertThat(response.regularMarketOpen()).isTrue();
        assertThat(response.marketPhase()).isEqualTo(StockExchangeMarketPhase.REGULAR_MARKET);
    }

    @Test
    void getStockExchangeStatusDetectsPostMarketHours() {
        when(stockExchangeRepository.findById(1L))
                .thenReturn(Optional.of(createExchange(1L, "New York Stock Exchange", "NYSE", "XNYS", true)));

        StockExchangeStatusResponse response = serviceAt("2026-04-06T21:00:00Z").getStockExchangeStatus(1L);

        assertThat(response.localTime()).isEqualTo(LocalTime.of(17, 0));
        assertThat(response.open()).isTrue();
        assertThat(response.regularMarketOpen()).isFalse();
        assertThat(response.marketPhase()).isEqualTo(StockExchangeMarketPhase.POST_MARKET);
    }

    @Test
    void getStockExchangeStatusClosesExchangeOnWeekend() {
        when(stockExchangeRepository.findById(1L))
                .thenReturn(Optional.of(createExchange(1L, "New York Stock Exchange", "NYSE", "XNYS", true)));

        StockExchangeStatusResponse response = serviceAt("2026-04-04T15:00:00Z").getStockExchangeStatus(1L);

        assertThat(response.localDate()).isEqualTo(LocalDate.of(2026, 4, 4));
        assertThat(response.holiday()).isFalse();
        assertThat(response.workingDay()).isFalse();
        assertThat(response.open()).isFalse();
        assertThat(response.marketPhase()).isEqualTo(StockExchangeMarketPhase.CLOSED);
    }

    @Test
    void getStockExchangeStatusBypassesChecksWhenExchangeIsInactive() {
        when(stockExchangeRepository.findById(1L))
                .thenReturn(Optional.of(createExchange(1L, "New York Stock Exchange", "NYSE", "XNYS", false)));

        StockExchangeStatusResponse response = serviceAt("2026-04-04T15:00:00Z").getStockExchangeStatus(1L);

        assertThat(response.holiday()).isFalse();
        assertThat(response.testModeBypassEnabled()).isTrue();
        assertThat(response.open()).isTrue();
        assertThat(response.regularMarketOpen()).isTrue();
        assertThat(response.marketPhase()).isEqualTo(StockExchangeMarketPhase.REGULAR_MARKET);
    }

    @Test
    void toggleStockExchangeActiveFlipsFlagAndPersistsEntity() {
        StockExchange exchange = createExchange(1L, "New York Stock Exchange", "NYSE", "XNYS", true);
        when(stockExchangeRepository.findById(1L)).thenReturn(Optional.of(exchange));
        when(stockExchangeRepository.save(exchange)).thenReturn(exchange);

        StockExchangeToggleResponse response = serviceAt("2026-04-06T14:30:00Z").toggleStockExchangeActive(1L);

        assertThat(response.isActive()).isFalse();
        assertThat(exchange.getIsActive()).isFalse();
        verify(stockExchangeRepository).save(exchange);
    }

    @Test
    void toggleStockExchangeActiveThrowsNotFoundWhenExchangeDoesNotExist() {
        when(stockExchangeRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt("2026-04-06T14:30:00Z").toggleStockExchangeActive(404L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void getStockExchangeStatusThrowsNotFoundWhenExchangeDoesNotExist() {
        when(stockExchangeRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt("2026-04-06T14:30:00Z").getStockExchangeStatus(404L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    private StockExchangeServiceImpl serviceAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new StockExchangeServiceImpl(stockExchangeRepository, clock);
    }

    private StockExchange createExchange(Long id, String name, String acronym, String micCode, boolean active) {
        StockExchange exchange = new StockExchange();
        exchange.setId(id);
        exchange.setExchangeName(name);
        exchange.setExchangeAcronym(acronym);
        exchange.setExchangeMICCode(micCode);
        exchange.setPolity("United States");
        exchange.setCurrency("USD");
        exchange.setTimeZone("America/New_York");
        exchange.setOpenTime(LocalTime.of(9, 30));
        exchange.setCloseTime(LocalTime.of(16, 0));
        exchange.setPreMarketOpenTime(LocalTime.of(7, 0));
        exchange.setPreMarketCloseTime(LocalTime.of(9, 30));
        exchange.setPostMarketOpenTime(LocalTime.of(16, 0));
        exchange.setPostMarketCloseTime(LocalTime.of(20, 0));
        exchange.setIsActive(active);
        return exchange;
    }
}
