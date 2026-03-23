package com.banka1.exchangeService.client;

import com.banka1.exchangeService.config.ExchangeRateProperties;
import com.banka1.exchangeService.dto.TwelveDataRateResponse;
import com.banka1.exchangeService.exception.BusinessException;
import com.banka1.exchangeService.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Testovi HTTP klijenta koji komunicira sa Twelve Data providerom.
 * Ako prolaze, znamo da se eksterni JSON odgovor pravilno validira i mapira,
 * a neispravni provider odgovori se pretvaraju u standardni BusinessException.
 */
class TwelveDataClientTest {

    private MockRestServiceServer server;
    private TwelveDataClient twelveDataClient;

    /**
     * Priprema mock HTTP server i RestClient instancu kako bi test bio potpuno
     * izolovan od stvarne mreze i spoljnog API-ja.
     */
    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        RestClient restClient = builder
                .baseUrl("https://api.twelvedata.com")
                .build();

        ExchangeRateProperties properties = new ExchangeRateProperties(
                "https://api.twelvedata.com",
                "demo-key",
                new BigDecimal("1.0"),
                new BigDecimal("0.70"),
                false
        );

        twelveDataClient = new TwelveDataClient(restClient, properties);
    }

    /**
     * Proverava uspesan scenario: validan provider odgovor mora da se mapira u
     * interni DTO sa pravilno procitanim valutnim parom, kursom i datumom.
     * Prolaz znaci da servis moze da koristi fetched kurs u daljoj logici.
     */
    @Test
    void fetchExchangeRateParsesSuccessfulResponse() {
        server.expect(requestTo(allOf(
                        containsString("/exchange_rate"),
                        containsString("symbol=EUR/RSD"),
                        containsString("apikey=demo-key")
                )))
                .andRespond(withSuccess("""
                        {
                          "symbol": "EUR/RSD",
                          "rate": "117.42000000",
                          "timestamp": 1774167300
                        }
                        """, MediaType.APPLICATION_JSON));

        TwelveDataRateResponse response = twelveDataClient.fetchExchangeRate("EUR", "RSD");

        assertThat(response.fromCurrency()).isEqualTo("EUR");
        assertThat(response.toCurrency()).isEqualTo("RSD");
        assertThat(response.rate()).isEqualByComparingTo(new BigDecimal("117.42000000"));
        assertThat(response.date()).hasToString("2026-03-22");
    }

    /**
     * Proverava da odgovor bez obaveznog `rate` polja ne prolazi tiho.
     * Prolaz znaci da necemo sacuvati poluispravan snapshot u bazu.
     */
    @Test
    void fetchExchangeRateThrowsWhenRateIsMissing() {
        server.expect(requestTo(allOf(
                        containsString("symbol=USD/RSD"),
                        containsString("apikey=demo-key")
                )))
                .andRespond(withSuccess("""
                        {
                          "symbol": "USD/RSD"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> twelveDataClient.fetchExchangeRate("USD", "RSD"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EXCHANGE_RATE_FETCH_FAILED);
    }

    /**
     * Proverava da provider error payload bude preveden u domen-specifican
     * exception umesto da se tretira kao validan odgovor.
     * Prolaz znaci da scheduler i rucni fetch mogu pravilno da aktiviraju
     * fallback logiku kada provider vrati gresku.
     */
    @Test
    void fetchExchangeRateThrowsBusinessExceptionWhenApiReturnsErrorPayload() {
        server.expect(requestTo(allOf(
                        containsString("symbol=CHF/RSD"),
                        containsString("apikey=demo-key")
                )))
                .andRespond(withSuccess("""
                        {
                          "code": 429,
                          "message": "API limit exceeded",
                          "status": "error"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> twelveDataClient.fetchExchangeRate("CHF", "RSD"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EXCHANGE_RATE_FETCH_FAILED);
    }

    /**
     * Proverava strogu validaciju timestamp polja.
     * Prolaz znaci da ne postoji "silent fallback" na danasnji datum kada
     * provider posalje neispravan timestamp.
     */
    @Test
    void fetchExchangeRateThrowsWhenTimestampIsInvalid() {
        server.expect(requestTo(allOf(
                        containsString("symbol=EUR/RSD"),
                        containsString("apikey=demo-key")
                )))
                .andRespond(withSuccess("""
                        {
                          "symbol": "EUR/RSD",
                          "rate": "117.42000000",
                          "timestamp": "not-a-timestamp"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> twelveDataClient.fetchExchangeRate("EUR", "RSD"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EXCHANGE_RATE_FETCH_FAILED);
    }
}
