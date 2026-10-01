package com.quantpulse.portfolio.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "portfolio")
public class Portfolio {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "base_currency", nullable = false, length = 3)
    private String baseCurrency = "MAD";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Portfolio() {
    }

    public Portfolio(String owner, String name, String baseCurrency) {
        this.owner = owner;
        this.name = name;
        this.baseCurrency = baseCurrency;
    }

    public UUID getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getBaseCurrency() { return baseCurrency; }
    public Instant getCreatedAt() { return createdAt; }
}
