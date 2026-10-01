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
 * Exchange rate of a currency pair for one day: units of quoteCurrency per 1 baseCurrency.
 * Key is (pair, date), so fetching again the same day updates the row.
 */
@Entity
@Table(name = "fx_rate")
@IdClass(FxRate.Key.class)
public class FxRate {

    @Id
    @Column(name = "base_currency", length = 3)
    private String baseCurrency;

    @Id
    @Column(name = "quote_currency", length = 3)
    private String quoteCurrency;

    @Id
    @Column(name = "rate_date")
    private LocalDate rateDate;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal rate;

    @Column(nullable = false, length = 32)
    private String source;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt = Instant.now();

    protected FxRate() {
    }

    public FxRate(String baseCurrency, String quoteCurrency, LocalDate rateDate,
                  BigDecimal rate, String source) {
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rateDate = rateDate;
        this.rate = rate;
        this.source = source;
    }

    /** Updates the rate for the same pair and day. */
    public void refresh(BigDecimal rate, String source) {
        this.rate = rate;
        this.source = source;
        this.ingestedAt = Instant.now();
    }

    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public LocalDate getRateDate() { return rateDate; }
    public BigDecimal getRate() { return rate; }
    public String getSource() { return source; }
    public Instant getIngestedAt() { return ingestedAt; }

    public static class Key implements Serializable {
        private String baseCurrency;
        private String quoteCurrency;
        private LocalDate rateDate;

        public Key() {
        }

        public Key(String baseCurrency, String quoteCurrency, LocalDate rateDate) {
            this.baseCurrency = baseCurrency;
            this.quoteCurrency = quoteCurrency;
            this.rateDate = rateDate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(baseCurrency, key.baseCurrency)
                    && Objects.equals(quoteCurrency, key.quoteCurrency)
                    && Objects.equals(rateDate, key.rateDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(baseCurrency, quoteCurrency, rateDate);
        }
    }
}
