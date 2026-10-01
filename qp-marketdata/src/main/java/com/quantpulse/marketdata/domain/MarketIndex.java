package com.quantpulse.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A market index (MASI, MASI20).
 * Here the code is the primary key: there are only two and they don't change.
 */
@Entity
@Table(name = "market_index")
public class MarketIndex {

    @Id
    @Column(length = 16)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(precision = 19, scale = 6)
    private BigDecimal value;

    @Column(name = "change_percent", precision = 12, scale = 6)
    private BigDecimal changePercent;

    @Column(name = "change_value", precision = 19, scale = 6)
    private BigDecimal changeValue;

    @Column(name = "as_of")
    private Instant asOf;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected MarketIndex() {
    }

    public MarketIndex(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /** @return true if the value changed, so the caller knows to send an event. */
    public boolean apply(BigDecimal value, BigDecimal changePercent,
                         BigDecimal changeValue, Instant asOf) {
        boolean changed = this.value == null || value == null || this.value.compareTo(value) != 0;
        this.value = value;
        this.changePercent = changePercent;
        this.changeValue = changeValue;
        this.asOf = asOf;
        this.updatedAt = Instant.now();
        return changed;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getValue() { return value; }
    public BigDecimal getChangePercent() { return changePercent; }
    public BigDecimal getChangeValue() { return changeValue; }
    public Instant getAsOf() { return asOf; }
    public Instant getUpdatedAt() { return updatedAt; }
}
