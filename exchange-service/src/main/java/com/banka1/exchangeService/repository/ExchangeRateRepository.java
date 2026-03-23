package com.banka1.exchangeService.repository;

import com.banka1.exchangeService.domain.ExchangeRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * JPA repozitorijum za lokalno sacuvane dnevne kurseve.
 */
public interface ExchangeRateRepository extends JpaRepository<ExchangeRateEntity, Long> {

    /**
     * Pronalazi kurs za zadatu valutu i datum.
     *
     * @param currencyCode kod valute koji se sastoji od 3 slova (primer: RSD)
     * @param date         datum kursa (LocalDate)
     * @return opcioni entitet kursa
     */
    Optional<ExchangeRateEntity> findByCurrencyCodeAndDate(String currencyCode, LocalDate date);

    /**
     * Vraca sve kurseve za zadati datum, sortirane po kodu valute.
     *
     * @param date datum snapshot-a
     * @return lista kurseva
     */
    List<ExchangeRateEntity> findAllByDateOrderByCurrencyCodeAsc(LocalDate date);

    /**
     * Vraca poslednji datum za koji postoji bilo koji kurs.
     *
     * @return poslednji snapshot datum ili {@code null} ako baza nema podatke
     */
    @Query("select max(e.date) from ExchangeRateEntity e")
    LocalDate findLatestDate();
}
