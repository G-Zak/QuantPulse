package com.quantpulse.insights.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** Model calls used per UTC day. Stored in Postgres so a restart doesn't reset it. */
@Entity
@Table(name = "llm_budget")
public class LlmBudget {

    @Id
    @Column(name = "budget_date")
    private LocalDate budgetDate;

    @Column(name = "daily_limit", nullable = false)
    private int dailyLimit;

    @Column(nullable = false)
    private int consumed;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected LlmBudget() {
    }

    public LlmBudget(LocalDate budgetDate, int dailyLimit) {
        this.budgetDate = budgetDate;
        this.dailyLimit = dailyLimit;
    }

    public int remaining() {
        return Math.max(0, dailyLimit - consumed);
    }

    public void spend() {
        consumed++;
        updatedAt = Instant.now();
    }

    public int getDailyLimit() { return dailyLimit; }
    public int getConsumed() { return consumed; }
}
