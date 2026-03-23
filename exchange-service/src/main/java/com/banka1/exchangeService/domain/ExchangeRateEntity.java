package com.banka1.exchangeService.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

/**
 * JPA entitet koji predstavlja jedan lokalno sacuvan kurs u tabeli {@code exchange_rate}.
 * Svaki red odgovara jednoj valuti za jedan datum snapshot-a.
 * Kombinacija {@code currency_code + rate_date} mora biti jedinstvena da baza
 * ne bi sadrzala duplikate za istu valutu i isti dan.
 */
@Entity
@Table(
        name = "exchange_rate",
        uniqueConstraints =
        @UniqueConstraint(
                name = "uk_exchange_rate_currency_date",
                columnNames = {"currency_code", "rate_date"}
        )
)
@Getter
@Setter
public class ExchangeRateEntity {
    /**
     * Tehnicki primarni kljuc reda u bazi.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Troslovni ISO kod valute, na primer {@code EUR} ili {@code USD}.
     */
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    /**
     * Kurs po kojem banka kupuje datu valutu od klijenta.
     */
    @Column(name = "buying_rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal buyingRate;

    /**
     * Kurs po kojem banka prodaje datu valutu klijentu.
     */
    @Column(name = "selling_rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal sellingRate;

    /**
     * Datum vazenja dnevnog snapshot-a kursa.
     */
    @Column(name = "rate_date", nullable = false)
    private LocalDate date;

    /**
     * Vreme kada je red prvi put upisan u bazu.
     */
    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    /**
     * Postavlja vreme kreiranja samo pri prvom upisu reda.
     */
    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = Timestamp.from(Instant.now());
        }
    }
}
