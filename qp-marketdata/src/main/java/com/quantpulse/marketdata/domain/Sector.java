package com.quantpulse.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A sector. The API returns 25 of them with French codes, some with accents (SANTÉ, TÉLÉC). */
@Entity
@Table(name = "sector")
public class Sector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "instrument_count", nullable = false)
    private int instrumentCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Sector() {
    }

    public Sector(String code, String name, int instrumentCount) {
        this.code = code;
        this.name = name;
        this.instrumentCount = instrumentCount;
    }

    public void update(String name, int instrumentCount) {
        this.name = name;
        this.instrumentCount = instrumentCount;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public int getInstrumentCount() { return instrumentCount; }
}
