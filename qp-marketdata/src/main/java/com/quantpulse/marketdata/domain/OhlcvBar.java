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
 * One daily bar. The ohlcv_bar table is partitioned by month.
 * JPA sees one table, Postgres puts each row in the right partition. Bulk upserts
 * go through OhlcvBatchWriter (JDBC batch with ON CONFLICT).
 */
@Entity
@Table(name = "ohlcv_bar")
@IdClass(OhlcvBar.Key.class)
public class OhlcvBar {

    @Id
    @Column(length = 16)
    private String ticker;

    @Id
    @Column(name = "session_date")
    private LocalDate sessionDate;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal open;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal high;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal low;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal close;

    /** Can be 0, 26 of ATW's 245 sessions had no trades. */
    @Column(nullable = false)
    private long volume;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt = Instant.now();

    protected OhlcvBar() {
    }

    public OhlcvBar(String ticker, LocalDate sessionDate, BigDecimal open, BigDecimal high,
                    BigDecimal low, BigDecimal close, long volume) {
        this.ticker = ticker;
        this.sessionDate = sessionDate;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }

    public String getTicker() { return ticker; }
    public LocalDate getSessionDate() { return sessionDate; }
    public BigDecimal getOpen() { return open; }
    public BigDecimal getHigh() { return high; }
    public BigDecimal getLow() { return low; }
    public BigDecimal getClose() { return close; }
    public long getVolume() { return volume; }
    public Instant getIngestedAt() { return ingestedAt; }

    /** Composite key. Must include sessionDate, the partition key. */
    public static class Key implements Serializable {
        private String ticker;
        private LocalDate sessionDate;

        public Key() {
        }

        public Key(String ticker, LocalDate sessionDate) {
            this.ticker = ticker;
            this.sessionDate = sessionDate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(ticker, key.ticker) && Objects.equals(sessionDate, key.sessionDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ticker, sessionDate);
        }
    }
}
