package com.banka1.exchangeService.service.impl;

import com.banka1.exchangeService.client.TwelveDataClient;
import com.banka1.exchangeService.config.ExchangeRateProperties;
import com.banka1.exchangeService.domain.ExchangeRateEntity;
import com.banka1.exchangeService.dto.ConversionRequestDto;
import com.banka1.exchangeService.dto.ConversionResponseDto;
import com.banka1.exchangeService.dto.ExchangeRateDto;
import com.banka1.exchangeService.dto.ExchangeRateFetchResponseDto;
import com.banka1.exchangeService.dto.TwelveDataRateResponse;
import com.banka1.exchangeService.exception.BusinessException;
import com.banka1.exchangeService.exception.ErrorCode;
import com.banka1.exchangeService.repository.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Jedinicni testovi glavne poslovne logike servisa.
 * Ova klasa proverava fetch, fallback, citanje snapshot-a i sva pravila
 * konverzije definisana specifikacijom.
 * Ako ovi testovi prolaze, servisni sloj je usaglasen sa dogovorenim ugovorom
 * i ne zavisi od stvarne baze ni eksternog providera.
 */
@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-03-23T08:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TwelveDataClient twelveDataClient;

    @Mock
    private ExchangeRateProperties exchangeRateProperties;

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    @InjectMocks
    private ExchangeRateServiceImpl exchangeRateService;

    /**
     * Priprema servis sa fiksnim satom radi deterministickih datuma u testovima
     * i podrazumevanom provizijom koju ne koristi svaki scenario.
     */
    @BeforeEach
    void setUp() {
        lenient().when(exchangeRateProperties.commissionPercentage()).thenReturn(new BigDecimal("0.70"));
        exchangeRateService = new ExchangeRateServiceImpl(twelveDataClient, exchangeRateProperties, exchangeRateRepository, FIXED_CLOCK);
    }

    /**
     * Proverava glavni happy path dnevnog fetch-a:
     * provider vraca market kurseve, servis iz njih racuna buying/selling rate
     * i upisuje svih 7 podrzanih stranih valuta.
     * Prolaz znaci da je marza ispravno primenjena pri persistiranju snapshot-a.
     */
    @Test
    void fetchAndStoreDailyRatesUsesConfiguredMarginToPersistBuyingAndSellingRates() {
        LocalDate date = LocalDate.of(2026, 3, 22);
        when(exchangeRateProperties.marginPercentage()).thenReturn(new BigDecimal("1.0"));
        when(twelveDataClient.fetchExchangeRate("EUR", "RSD"))
                .thenReturn(new TwelveDataRateResponse("EUR", "RSD", new BigDecimal("117.40"), date));
        when(twelveDataClient.fetchExchangeRate("CHF", "RSD"))
                .thenReturn(new TwelveDataRateResponse("CHF", "RSD", new BigDecimal("120.40"), date));
        when(twelveDataClient.fetchExchangeRate("USD", "RSD"))
                .thenReturn(new TwelveDataRateResponse("USD", "RSD", new BigDecimal("108.40"), date));
        when(twelveDataClient.fetchExchangeRate("GBP", "RSD"))
                .thenReturn(new TwelveDataRateResponse("GBP", "RSD", new BigDecimal("137.40"), date));
        when(twelveDataClient.fetchExchangeRate("JPY", "RSD"))
                .thenReturn(new TwelveDataRateResponse("JPY", "RSD", new BigDecimal("0.72"), date));
        when(twelveDataClient.fetchExchangeRate("CAD", "RSD"))
                .thenReturn(new TwelveDataRateResponse("CAD", "RSD", new BigDecimal("79.40"), date));
        when(twelveDataClient.fetchExchangeRate("AUD", "RSD"))
                .thenReturn(new TwelveDataRateResponse("AUD", "RSD", new BigDecimal("70.40"), date));
        when(exchangeRateRepository.findByCurrencyCodeAndDate(any(), eq(date))).thenReturn(Optional.empty());
        when(exchangeRateRepository.save(any(ExchangeRateEntity.class))).thenAnswer(invocation -> {
            ExchangeRateEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            entity.setCreatedAt(Timestamp.from(Instant.parse("2026-03-22T10:15:30Z")));
            return entity;
        });

        ExchangeRateFetchResponseDto response = exchangeRateService.fetchAndStoreDailyRates();

        assertThat(response.fetchedCount()).isEqualTo(7);
        assertThat(response.rates()).extracting(ExchangeRateDto::currencyCode)
                .containsExactlyInAnyOrder("EUR", "CHF", "USD", "GBP", "JPY", "CAD", "AUD");

        ArgumentCaptor<ExchangeRateEntity> captor = ArgumentCaptor.forClass(ExchangeRateEntity.class);
        verify(exchangeRateRepository, org.mockito.Mockito.times(7)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ExchangeRateEntity::getSellingRate)
                .containsExactlyInAnyOrder(
                        new BigDecimal("118.57400000"),
                        new BigDecimal("121.60400000"),
                        new BigDecimal("109.48400000"),
                        new BigDecimal("138.77400000"),
                        new BigDecimal("0.72720000"),
                        new BigDecimal("80.19400000"),
                        new BigDecimal("71.10400000")
                );
        assertThat(captor.getAllValues())
                .extracting(ExchangeRateEntity::getBuyingRate)
                .containsExactlyInAnyOrder(
                        new BigDecimal("116.22600000"),
                        new BigDecimal("119.19600000"),
                        new BigDecimal("107.31600000"),
                        new BigDecimal("136.02600000"),
                        new BigDecimal("0.71280000"),
                        new BigDecimal("78.60600000"),
                        new BigDecimal("69.69600000")
                );
    }

    /**
     * Proverava da citanje liste bez prosledjenog datuma uzima poslednji
     * raspolozivi snapshot iz baze.
     * Prolaz znaci da `GET /rates` moze bez dodatnih parametara da vrati
     * "trenutnu" kursnu listu.
     */
    @Test
    void getRatesUsesLatestSnapshotWhenDateIsMissing() {
        LocalDate latestDate = LocalDate.of(2026, 3, 22);
        when(exchangeRateRepository.findLatestDate()).thenReturn(latestDate);
        when(exchangeRateRepository.findAllByDateOrderByCurrencyCodeAsc(latestDate))
                .thenReturn(List.of(entity("EUR", "117.10", "117.90", latestDate)));

        List<ExchangeRateDto> rates = exchangeRateService.getRates(null);

        assertThat(rates).hasSize(1);
        assertThat(rates.getFirst().date()).isEqualTo(latestDate);
    }

    /**
     * Proverava fallback scenario kada provider nije dostupan.
     * Prolaz znaci da servis kopira prethodni lokalni snapshot na novi datum i
     * ne ostavlja sistem bez kursne liste za tekući dan.
     */
    @Test
    void fetchAndStoreDailyRatesFallsBackToPreviousSnapshotWhenProviderFails() {
        LocalDate previousDate = LocalDate.of(2026, 3, 22);
        LocalDate fallbackDate = LocalDate.of(2026, 3, 23);
        when(twelveDataClient.fetchExchangeRate("EUR", "RSD"))
                .thenThrow(new BusinessException(ErrorCode.EXCHANGE_RATE_FETCH_FAILED, "Provider unavailable"));
        when(exchangeRateRepository.findLatestDate()).thenReturn(previousDate);
        when(exchangeRateRepository.findAllByDateOrderByCurrencyCodeAsc(previousDate))
                .thenReturn(List.of(
                        entity("EUR", "117.40", "117.40", previousDate),
                        entity("USD", "108.40", "108.40", previousDate)
                ));
        when(exchangeRateRepository.findByCurrencyCodeAndDate("EUR", fallbackDate)).thenReturn(Optional.empty());
        when(exchangeRateRepository.findByCurrencyCodeAndDate("USD", fallbackDate)).thenReturn(Optional.empty());
        when(exchangeRateRepository.save(any(ExchangeRateEntity.class))).thenAnswer(invocation -> {
            ExchangeRateEntity entity = invocation.getArgument(0);
            entity.setId(1L);
            entity.setCreatedAt(Timestamp.from(Instant.parse("2026-03-23T08:00:00Z")));
            return entity;
        });

        ExchangeRateFetchResponseDto response = exchangeRateService.fetchAndStoreDailyRates();

        assertThat(response.fetchedCount()).isEqualTo(2);
        assertThat(response.rates()).extracting(ExchangeRateDto::date)
                .containsOnly(fallbackDate);
        assertThat(response.rates()).extracting(ExchangeRateDto::currencyCode)
                .containsExactlyInAnyOrder("EUR", "USD");
    }

    /**
     * Proverava specijalan tretman bazne valute `RSD`.
     * Prolaz znaci da sistem ne zavisi od baze ni providera za sinteticki kurs
     * 1:1 za domacu valutu.
     */
    @Test
    void getRateReturnsSyntheticBaseCurrencyRate() {
        when(exchangeRateRepository.findLatestDate()).thenReturn(LocalDate.of(2026, 3, 22));

        ExchangeRateDto rate = exchangeRateService.getRate("rsd", null);

        assertThat(rate.currencyCode()).isEqualTo("RSD");
        assertThat(rate.buyingRate()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(rate.sellingRate()).isEqualByComparingTo(BigDecimal.ONE);
    }

    /**
     * Proverava acceptance criterion "same currency -> same amount".
     * Prolaz znaci da se za konverziju iste valute ne radi nepotrebna matematika
     * niti se naplacuje provizija.
     */
    @Test
    void convertSameCurrencyReturnsSameAmountWithoutCommission() {
        LocalDate latestDate = LocalDate.of(2026, 3, 22);
        when(exchangeRateRepository.findLatestDate()).thenReturn(latestDate);

        ConversionResponseDto response = exchangeRateService.convert(
                new ConversionRequestDto(new BigDecimal("100.00"), "EUR", "EUR", null)
        );

        assertThat(response.fromCurrency()).isEqualTo("EUR");
        assertThat(response.toCurrency()).isEqualTo("EUR");
        assertThat(response.fromAmount()).isEqualByComparingTo("100.00");
        assertThat(response.toAmount()).isEqualByComparingTo("100.00");
        assertThat(response.rate()).isEqualByComparingTo("1.00000000");
        assertThat(response.commission()).isEqualByComparingTo("0.00");
        assertThat(response.date()).isEqualTo(latestDate);
    }

    /**
     * Proverava glavni foreign-to-foreign scenario iz specifikacije.
     * Prolaz znaci da servis koristi `sellingRate` i za ulazak u `RSD` i za
     * izlazak iz `RSD`, uz obracun efektivnog kursa i provizije.
     */
    @Test
    void convertUsesSellingRateForBothCurrenciesViaRsd() {
        LocalDate latestDate = LocalDate.of(2026, 3, 22);
        when(exchangeRateRepository.findLatestDate()).thenReturn(latestDate);
        when(exchangeRateRepository.findByCurrencyCodeAndDate("EUR", latestDate))
                .thenReturn(Optional.of(entity("EUR", "117.00", "118.00", latestDate)));
        when(exchangeRateRepository.findByCurrencyCodeAndDate("USD", latestDate))
                .thenReturn(Optional.of(entity("USD", "107.00", "108.00", latestDate)));

        ConversionResponseDto response = exchangeRateService.convert(
                new ConversionRequestDto(new BigDecimal("100.00"), "EUR", "USD", null)
        );

        assertThat(response.toAmount()).isEqualByComparingTo("109.25925926");
        assertThat(response.rate()).isEqualByComparingTo("1.09259259");
        assertThat(response.commission()).isEqualByComparingTo("0.70");
        assertThat(response.date()).isEqualTo(latestDate);
    }

    /**
     * Proverava `RSD -> foreign` scenario.
     * Prolaz znaci da servis koristi samo selling rate ciljne valute i pravilno
     * racuna rezultat i proviziju.
     */
    @Test
    void convertFromRsdUsesOnlyTargetSellingRate() {
        LocalDate latestDate = LocalDate.of(2026, 3, 22);
        when(exchangeRateRepository.findLatestDate()).thenReturn(latestDate);
        when(exchangeRateRepository.findByCurrencyCodeAndDate("USD", latestDate))
                .thenReturn(Optional.of(entity("USD", "107.00", "108.00", latestDate)));

        ConversionResponseDto response = exchangeRateService.convert(
                new ConversionRequestDto(new BigDecimal("216.00"), "RSD", "USD", null)
        );

        assertThat(response.toAmount()).isEqualByComparingTo("2.00000000");
        assertThat(response.rate()).isEqualByComparingTo("0.00925926");
        assertThat(response.commission()).isEqualByComparingTo("1.51");
    }

    /**
     * Proverava `foreign -> RSD` scenario.
     * Prolaz znaci da servis koristi samo selling rate izvorne valute pri
     * ulasku u baznu valutu.
     */
    @Test
    void convertToRsdUsesOnlySourceSellingRate() {
        LocalDate latestDate = LocalDate.of(2026, 3, 22);
        when(exchangeRateRepository.findLatestDate()).thenReturn(latestDate);
        when(exchangeRateRepository.findByCurrencyCodeAndDate("USD", latestDate))
                .thenReturn(Optional.of(entity("USD", "107.00", "108.00", latestDate)));

        ConversionResponseDto response = exchangeRateService.convert(
                new ConversionRequestDto(new BigDecimal("2.00"), "USD", "RSD", null)
        );

        assertThat(response.toAmount()).isEqualByComparingTo("216.00000000");
        assertThat(response.rate()).isEqualByComparingTo("108.00000000");
        assertThat(response.commission()).isEqualByComparingTo("0.01");
    }

    /**
     * Proverava neuspesan scenario kada ne postoji nijedan lokalni snapshot.
     * Prolaz znaci da servis ne vraca izmisljene vrednosti kada baza nema kurseve.
     */
    @Test
    void convertThrowsWhenSnapshotDoesNotExist() {
        when(exchangeRateRepository.findLatestDate()).thenReturn(null);

        assertThatThrownBy(() -> exchangeRateService.convert(
                new ConversionRequestDto(new BigDecimal("10"), "EUR", "USD", null)
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.EXCHANGE_RATE_NOT_FOUND);
    }

    /**
     * Proverava validaciju nepodrzane valute pre rada sa bazom.
     * Prolaz znaci da servis odbija nepoznate ISO kodove konzistentnom
     * domen-specificnom greskom.
     */
    @Test
    void getRateThrowsWhenCurrencyIsNotSupported() {
        assertThatThrownBy(() -> exchangeRateService.getRate("NOK", LocalDate.of(2026, 3, 22)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNSUPPORTED_CURRENCY);
    }

    /**
     * Pomocna metoda za pravljenje test entiteta kursa sa stabilnim timestamp-om.
     */
    private ExchangeRateEntity entity(String currency, String buyingRate, String sellingRate, LocalDate date) {
        ExchangeRateEntity entity = new ExchangeRateEntity();
        entity.setCurrencyCode(currency);
        entity.setBuyingRate(new BigDecimal(buyingRate));
        entity.setSellingRate(new BigDecimal(sellingRate));
        entity.setDate(date);
        entity.setCreatedAt(Timestamp.from(Instant.parse("2026-03-22T10:15:30Z")));
        return entity;
    }
}
