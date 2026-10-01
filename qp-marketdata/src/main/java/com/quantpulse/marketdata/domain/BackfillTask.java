package com.quantpulse.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One instrument's history backfill, saved as a task.
 *
 * A year of bars for all 81 instruments is 81 calls, most of the daily quota. Saving
 * each fetch as a task lets the scheduler do a few per day, most important first,
 * and continue after a restart.
 */
@Entity
@Table(name = "backfill_task")
public class BackfillTask {

    public enum Status { PENDING, RUNNING, DONE, FAILED }

    /** What to fetch. Both kinds share one queue so they share the quota. */
    public enum Kind { OHLCV, DIVIDEND, FUNDAMENTALS }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Column(name = "range_code", nullable = false, length = 8)
    private String rangeCode = "1Y";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind = Kind.OHLCV;

    /** Higher runs first. Based on market cap, so big names get history first. */
    @Column(nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected BackfillTask() {
    }

    public BackfillTask(String ticker, String rangeCode, int priority) {
        this(ticker, rangeCode, priority, Kind.OHLCV);
    }

    public BackfillTask(String ticker, String rangeCode, int priority, Kind kind) {
        this.ticker = ticker;
        this.rangeCode = rangeCode;
        this.priority = priority;
        this.kind = kind;
    }

    public void markRunning() {
        this.status = Status.RUNNING;
        this.attempts++;
    }

    public void markDone() {
        this.status = Status.DONE;
        this.completedAt = Instant.now();
        this.lastError = null;
    }

    /**
     * 3 attempts max. It's usually a 404 (delisted) or no quota left, and retrying
     * costs calls.
     */
    public void markFailed(String error) {
        this.lastError = error != null && error.length() > 1000 ? error.substring(0, 1000) : error;
        this.status = attempts >= 3 ? Status.FAILED : Status.PENDING;
    }

    public Long getId() { return id; }
    public String getTicker() { return ticker; }
    public String getRangeCode() { return rangeCode; }
    public Kind getKind() { return kind; }
    public int getPriority() { return priority; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCompletedAt() { return completedAt; }
}
