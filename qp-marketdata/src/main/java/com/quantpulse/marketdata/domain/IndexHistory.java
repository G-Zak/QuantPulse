package com.quantpulse.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Daily close of an index. Used as the benchmark for beta and alpha. */
@Entity
@Table(name = "index_history")
@IdClass(IndexHistory.Key.class)
public class IndexHistory {

    @Id
    @Column(length = 16)
    private String code;

    @Id
    @Column(name = "session_date")
    private LocalDate sessionDate;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal value;

    @Column(name = "change_percent", precision = 12, scale = 6)
    private BigDecimal changePercent;

    @Column(name = "change_value", precision = 19, scale = 6)
    private BigDecimal changeValue;

    protected IndexHistory() {
    }

    public IndexHistory(String code, LocalDate sessionDate, BigDecimal value,
                        BigDecimal changePercent, BigDecimal changeValue) {
        this.code = code;
        this.sessionDate = sessionDate;
        this.value = value;
        this.changePercent = changePercent;
        this.changeValue = changeValue;
    }

    public String getCode() { return code; }
    public LocalDate getSessionDate() { return sessionDate; }
    public BigDecimal getValue() { return value; }
    public BigDecimal getChangePercent() { return changePercent; }
    public BigDecimal getChangeValue() { return changeValue; }

    public static class Key implements Serializable {
        private String code;
        private LocalDate sessionDate;

        public Key() {
        }

        public Key(String code, LocalDate sessionDate) {
            this.code = code;
            this.sessionDate = sessionDate;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(code, key.code) && Objects.equals(sessionDate, key.sessionDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, sessionDate);
        }
    }
}
