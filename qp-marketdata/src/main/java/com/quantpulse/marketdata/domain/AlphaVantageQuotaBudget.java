package com.quantpulse.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Alpha Vantage calls used today. Same idea as QuotaBudget.
 *
 * Drahmi tells us how many calls are left in a header. Alpha Vantage doesn't: once we're
 * out it returns 200 with a message instead of data. So when it says no, today is over.
 */
@Entity
@Table(name = "alphavantage_quota_budget")
public class AlphaVantageQuotaBudget {

    @Id
    @Column(name = "quota_date")
    private LocalDate quotaDate;

    @Column(name = "daily_limit", nullable = false)
    private int dailyLimit;

    @Column(nullable = false)
    private int consumed;

    @Column(name = "upstream_exhausted", nullable = false)
    private boolean upstreamExhausted;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AlphaVantageQuotaBudget() {
    }

    public AlphaVantageQuotaBudget(LocalDate quotaDate, int dailyLimit) {
        this.quotaDate = quotaDate;
        this.dailyLimit = dailyLimit;
    }

    public int remaining() {
        return upstreamExhausted ? 0 : Math.max(0, dailyLimit - consumed);
    }

    public void spend(int calls) {
        this.consumed += calls;
        this.updatedAt = Instant.now();
    }

    /** The API refused for being over the limit. Nothing more today. */
    public void markUpstreamExhausted() {
        this.upstreamExhausted = true;
        this.updatedAt = Instant.now();
    }

    public LocalDate getQuotaDate() { return quotaDate; }
    public int getDailyLimit() { return dailyLimit; }
    public int getConsumed() { return consumed; }
    public boolean isUpstreamExhausted() { return upstreamExhausted; }
    public Instant getUpdatedAt() { return updatedAt; }
}
