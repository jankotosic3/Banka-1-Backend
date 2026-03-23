package com.banka1.exchangeService.service;

import com.banka1.exchangeService.config.ExchangeRateProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Testovi scheduler komponente koja pokrece dnevni refresh i startup fetch.
 * Ako prolaze, znamo da scheduler ne sadrzi sopstvenu biznis logiku vec da
 * pravilno delegira rad servisnom sloju u odgovarajucim situacijama.
 */
@ExtendWith(MockitoExtension.class)
class ExchangeRateSchedulerTest {

    @Mock
    private ExchangeRateService exchangeRateService;

    @InjectMocks
    private ExchangeRateScheduler exchangeRateScheduler;

    /**
     * Proverava da cron metoda uvek delegira na servisni refresh.
     * Prolaz znaci da zakazani posao zaista aktivira fetch dnevnih kurseva.
     */
    @Test
    void refreshDailyRatesDelegatesToService() {
        exchangeRateScheduler = new ExchangeRateScheduler(exchangeRateService, properties(false));

        exchangeRateScheduler.refreshDailyRates();

        verify(exchangeRateService).fetchAndStoreDailyRates();
    }

    /**
     * Proverava startup fetch kada je feature ukljucen preko konfiguracije.
     * Prolaz znaci da servis pri podizanju moze automatski da napuni bazu.
     */
    @Test
    void fetchOnStartupCallsServiceOnlyWhenEnabled() {
        exchangeRateScheduler = new ExchangeRateScheduler(exchangeRateService, properties(true));

        exchangeRateScheduler.fetchOnStartup();

        verify(exchangeRateService).fetchAndStoreDailyRates();
    }

    /**
     * Proverava da startup fetch ne radi nista kada je konfiguraciono iskljucen.
     * Prolaz znaci da razvojna i test okruzenja mogu bezbedno da izbegnu
     * automatski eksterni fetch.
     */
    @Test
    void fetchOnStartupSkipsWhenDisabled() {
        exchangeRateScheduler = new ExchangeRateScheduler(exchangeRateService, properties(false));

        exchangeRateScheduler.fetchOnStartup();

        verify(exchangeRateService, never()).fetchAndStoreDailyRates();
    }

    /**
     * Pomocna fabrika konfiguracije za scheduler testove.
     */
    private ExchangeRateProperties properties(boolean fetchOnStartup) {
        return new ExchangeRateProperties(
                "https://api.twelvedata.com",
                "demo-key",
                new BigDecimal("1.0"),
                new BigDecimal("0.70"),
                fetchOnStartup
        );
    }
}
