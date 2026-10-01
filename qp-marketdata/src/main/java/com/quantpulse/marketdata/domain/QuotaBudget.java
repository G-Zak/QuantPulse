package com.quantpulse.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Drahmi calls used today.
 *
 * Stored in Postgres, not Redis: Redis runs without persistence here. If we lost this
 * counter we wouldn't know how much of the 100 calls are left until midnight UTC.
 */
@Entity
@Table(name = "quota_budget")
public class QuotaBudget {

    /** UTC date, same as the API's reset. */
    @Id
    @Column(name = "quota_date")
    private LocalDate quotaDate;

    @Column(name = "daily_limit", nullable = false)
    private int dailyLimit;

    @Column(nullable = false)
    private int consumed;

    /**
     * Last X-RateLimit-Remaining from the API. If it's different from our count,
     * someone else is using the key.
     */
    @Column(name = "upstream_remaining")
    private Integer upstreamRemaining;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected QuotaBudget() {
    }

    public QuotaBudget(LocalDate quotaDate, int dailyLimit) {
        this.quotaDate = quotaDate;
        this.dailyLimit = dailyLimit;
    }

    public int remaining() {
        return Math.max(0, dailyLimit - consumed);
    }

    public boolean canSpend(int calls) {
        return remaining() >= calls;
    }

    public void spend(int calls) {
        this.consumed += calls;
        this.updatedAt = Instant.now();
    }

    /**
     * If the API says fewer calls are left than we think, trust the API.
     * Guessing too high means a 429 in the middle of ingestion.
     */
    public void reconcile(Integer upstreamRemaining) {
        if (upstreamRemaining == null) {
            return;
        }
        this.upstreamRemaining = upstreamRemaining;
        int impliedConsumed = dailyLimit - upstreamRemaining;
        if (impliedConsumed > this.consumed) {
            this.consumed = impliedConsumed;
        }
        this.updatedAt = Instant.now();
    }

    public LocalDate getQuotaDate() { return quotaDate; }
    public int getDailyLimit() { return dailyLimit; }
    public int getConsumed() { return consumed; }
    public Integer getUpstreamRemaining() { return upstreamRemaining; }
    public Instant getUpdatedAt() { return updatedAt; }
}
