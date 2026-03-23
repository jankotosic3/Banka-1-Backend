package com.banka1.exchangeService.service;

import com.banka1.exchangeService.dto.ConversionRequestDto;
import com.banka1.exchangeService.dto.ConversionResponseDto;
import com.banka1.exchangeService.dto.ExchangeRateDto;
import com.banka1.exchangeService.dto.ExchangeRateFetchResponseDto;

import java.time.LocalDate;
import java.util.List;

/**
 * Glavni servis za rad sa deviznim kursevima i konverzijama.
 */
public interface ExchangeRateService {

    /**
     * Dohvata dnevne kurseve sa eksternog API-ja i cuva ih lokalno.
     * Scheduler zove ovo svaki dan u 08.00h
     * Ako eksterni fetch padne, koristi poslednji lokalni snapshot kao fallback za novi dnevni unos.
     *
     * @return rezultat fetch operacije sa sacuvanim kursevima
     */
    ExchangeRateFetchResponseDto fetchAndStoreDailyRates();

    /**
     * Vraca sve kurseve za trazeni datum ili poslednji raspolozivi snapshot.
     *
     * @param date datum snapshot-a; ako je {@code null}, vraca se poslednji raspolozivi datum
     * @return lista kurseva
     */
    List<ExchangeRateDto> getRates(LocalDate date);

    /**
     * Vraca kurs za jednu valutu za zadati datum ili poslednji raspolozivi snapshot.
     *
     * @param currencyCode troslovni ISO kod valute
     * @param date         datum snapshot-a; ako je {@code null}, vraca se poslednji raspolozivi datum
     * @return kurs valute
     */
    ExchangeRateDto getRate(String currencyCode, LocalDate date);

    /**
     * Konvertuje iznos koristeci lokalno sacuvane kurseve i pravilo da sve ide preko RSD.
     *
     * @param request zahtev za konverziju
     * @return rezultat konverzije
     */
    ConversionResponseDto convert(ConversionRequestDto request);
}
