package com.banka1.exchangeService.controller;

import com.banka1.exchangeService.dto.ConversionQueryDto;
import com.banka1.exchangeService.dto.ConversionRequestDto;
import com.banka1.exchangeService.dto.ConversionResponseDto;
import com.banka1.exchangeService.dto.ExchangeRateDto;
import com.banka1.exchangeService.dto.ExchangeRateFetchResponseDto;
import com.banka1.exchangeService.service.ExchangeRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * REST kontroler za rad sa lokalno sacuvanim dnevnim kursevima i kalkulacijom
 * valutne ekvivalencije.
 * Javne rute u samom servisu su bez gateway prefiksa:
 * {@code /rates}, {@code /rates/{currencyCode}} i {@code /calculate}.
 * Kada zahtev prolazi kroz API gateway, isti endpointi su dostupni pod
 * prefiksom {@code /api/exchange}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Exchange Rates", description = "Exchange rate storage and conversion endpoints")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    /**
     * Rucno pokrece fetch dnevnih kurseva za sve podrzane strane valute i
     * cuva ih kao lokalni snapshot.
     * Ovaj endpoint koristi istu logiku kao i zakazani dnevni cron posao.
     *
     * @return rezultat fetch operacije sa brojem obradjenih valuta i listom
     *         sacuvanih kurseva
     */
    @PostMapping("/rates/fetch")
    @Operation(
            summary = "Fetch and store daily exchange rates",
            responses = @ApiResponse(
                    responseCode = "200",
                    description = "Rates fetched and stored",
                    content = @Content(schema = @Schema(implementation = ExchangeRateFetchResponseDto.class))
            )
    )
    public ExchangeRateFetchResponseDto fetchRates() {
        return exchangeRateService.fetchAndStoreDailyRates();
    }

    /**
     * Vraca kursnu listu za trazeni datum ili, ako datum nije prosledjen,
     * poslednji dostupni lokalni snapshot.
     *
     * @param date opcioni datum snapshot-a
     * @return lista dnevnih kurseva sortirana po kodu valute
     */
    @GetMapping("/rates")
    @Operation(
            summary = "Get stored exchange rates",
            responses = @ApiResponse(
                    responseCode = "200",
                    description = "Stored exchange rates",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ExchangeRateDto.class)))
            )
    )
    public List<ExchangeRateDto> getRates(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return exchangeRateService.getRates(date);
    }

    /**
     * Vraca kurs za jednu konkretnu valutu za trazeni datum ili za poslednji
     * lokalno raspolozivi snapshot ako datum nije zadat.
     *
     * @param currencyCode troslovni ISO kod valute, na primer {@code EUR}
     * @param date opcioni datum snapshot-a
     * @return jedan zapis kursa za trazenu valutu
     */
    @GetMapping("/rates/{currencyCode}")
    @Operation(summary = "Get a single exchange rate")
    public ExchangeRateDto getRate(
            @PathVariable String currencyCode,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return exchangeRateService.getRate(currencyCode, date);
    }

    /**
     * Racuna ekvivalent iznosa iz jedne valute u drugu.
     * Konverzija uvek ide preko {@code RSD} kao bazne valute, a za obracun se
     * koristi prodajni kurs ne-RSD valute prema pravilima zadatka.
     * Ulazni podaci se preuzimaju iz query parametara URL-a, na primer:
     * {@code /calculate?fromCurrency=EUR&toCurrency=USD&amount=100}.
     *
     * @param request query DTO koji Spring automatski popunjava iz URL query
     *                parametara
     * @return rezultat kalkulacije sa izlaznim iznosom, efektivnim kursom,
     *         provizijom i datumom kursne liste
     */
    @GetMapping("/calculate")
    @Operation(summary = "Calculate currency equivalence via RSD base")
    public ConversionResponseDto calculate(@Valid ConversionQueryDto request) {
        return exchangeRateService.convert(new ConversionRequestDto(
                request.getAmount(),
                request.getFromCurrency(),
                request.getToCurrency(),
                request.getDate()
        ));
    }
}
