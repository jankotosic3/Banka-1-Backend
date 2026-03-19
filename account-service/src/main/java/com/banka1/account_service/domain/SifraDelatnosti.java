package com.banka1.account_service.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Entity
@org.hibernate.annotations.Immutable
@Table(
        name = "sifra_delatnosti_table"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SifraDelatnosti extends BaseEntity{
    @Column(nullable = false,updatable = false)
    private String oznaka;
    @ElementCollection
    @CollectionTable(
            name = "sifra_delatnosti_sektori",
            joinColumns = @JoinColumn(name = "sifra_delatnosti_id")
    )
    @Column(name = "country", nullable = false)
    private Set<String> sektori = new HashSet<>();
    @Column(nullable = false,updatable = false)
    private String grana;

    public Set<String> getSektori() {
        return Collections.unmodifiableSet(sektori);
    }
}
