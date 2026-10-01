package com.quantpulse.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Daily close of a world benchmark (SPY, EEM), in its own currency.
 * Only the close is kept, that's all the comparison uses.
 */
@Entity
@Table(name = "benchmark_history")
@IdClass(BenchmarkBar.Key.class)
public class BenchmarkBar {

    @Id
    @Column(length = 16)
    private String symbol;

    @Id
    @Column(name = "session_date")
    private LocalDate sessionDate;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal close;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt = Instant.now();

    protected BenchmarkBar() {
    }

    public BenchmarkBar(String symbol, LocalDate sessionDate, BigDecimal close, String currency, String source) {
        this.symbol = symbol;
        this.sessionDate = sessionDate;
        this.close = close;
        this.currency = currency;
        this.source = source;
    }

    public String getSymbol() { return symbol; }
    public LocalDate getSessionDate() { return sessionDate; }
    public BigDecimal getClose() { return close; }
    public String getCurrency() { return currency; }
    public String getSource() { return source; }

    public static class Key implements Serializable {
        private String symbol;
        private LocalDate sessionDate;

        public Key() {
        }

        public Key(String symbol, LocalDate sessionDate) {
            this.symbol = symbol;
            this.sessionDate = sessionDate;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(symbol, k.symbol) && Objects.equals(sessionDate, k.sessionDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(symbol, sessionDate);
        }
    }
}
