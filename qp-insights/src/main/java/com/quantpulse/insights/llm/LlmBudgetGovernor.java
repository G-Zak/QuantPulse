package com.quantpulse.insights.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Limits model calls per UTC day, like the quota governors in qp-marketdata
 * but for a paid API.
 *
 * The call is counted before it's made, in its own transaction (REQUIRES_NEW),
 * so a failed call still counts. It may have been billed anyway.
 */
@Component
public class LlmBudgetGovernor {

    private final LlmBudgetRepository budgets;
    private final Clock clock;
    private final int dailyLimit;

    public LlmBudgetGovernor(LlmBudgetRepository budgets, Clock clock,
                             @Value("${quantpulse.insights.daily-limit:5}") int dailyLimit) {
        this.budgets = budgets;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire() {
        LocalDate today = LocalDate.now(clock);
        LlmBudget budget = budgets.findForUpdate(today).orElseGet(() -> budgets.saveAndFlush(new LlmBudget(today, dailyLimit)));
        if (budget.remaining() <= 0) {
            return false;
        }
        budget.spend();
        return true;
    }

    @Transactional(readOnly = true)
    public int remainingToday() {
        return budgets.findById(LocalDate.now(clock)).map(LlmBudget::remaining).orElse(dailyLimit);
    }
}
