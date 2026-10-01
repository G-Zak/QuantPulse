package com.quantpulse.marketdata.quota;

import com.quantpulse.marketdata.domain.AlphaVantageQuotaBudget;
import com.quantpulse.marketdata.repository.AlphaVantageQuotaBudgetRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limits Alpha Vantage calls (25 a day on the free tier).
 *
 * Same idea as QuotaGovernor, but kept separate because:
 * - the floors are different (Drahmi's BACKFILL floor of 35 is more than 25),
 * - Alpha Vantage doesn't tell us how many calls are left, it just refuses,
 * - changing quota_budget would touch the Drahmi governor, which matters more.
 * See ADR-006.
 *
 *   remaining  25  all jobs run
 *               5  NORMAL stops (benchmarks, lookups)
 *               0  CRITICAL can use the last call (daily FX refresh)
 */
@Service
public class AlphaVantageQuotaGovernor {

    private static final Logger log = LoggerFactory.getLogger(AlphaVantageQuotaGovernor.class);

    public enum Priority {
        /** The daily FX refresh. */
        CRITICAL(0),
        /** Benchmarks and anything that can wait until tomorrow. */
        NORMAL(5);

        private final int floor;

        Priority(int floor) {
            this.floor = floor;
        }

        public int floor() {
            return floor;
        }
    }

    private final AlphaVantageQuotaBudgetRepository budgets;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int dailyLimit;

    private final AtomicInteger remainingGauge = new AtomicInteger();
    private final AtomicInteger consumedGauge = new AtomicInteger();

    public AlphaVantageQuotaGovernor(AlphaVantageQuotaBudgetRepository budgets,
                                     JdbcTemplate jdbc,
                                     MeterRegistry meterRegistry,
                                     Clock clock,
                                     @Value("${quantpulse.alphavantage.daily-limit:25}") int dailyLimit) {
        this.budgets = budgets;
        this.jdbc = jdbc;
        this.clock = clock;
        this.dailyLimit = dailyLimit;

        Gauge.builder("quantpulse.alphavantage.quota.remaining", remainingGauge, AtomicInteger::get)
                .description("Alpha Vantage calls still available today")
                .register(meterRegistry);
        Gauge.builder("quantpulse.alphavantage.quota.consumed", consumedGauge, AtomicInteger::get)
                .description("Alpha Vantage calls spent today")
                .register(meterRegistry);
    }

    /** Sets the gauges at startup and keeps them in sync (see QuotaGovernor.refreshGauges). */
    @jakarta.annotation.PostConstruct
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${quantpulse.quota.gauge-refresh-ms:60000}")
    @Transactional(readOnly = true)
    public void refreshGauges() {
        publishGauges(budgets.findById(today())
                .orElseGet(() -> new AlphaVantageQuotaBudget(today(), dailyLimit)));
    }

    /**
     * Reserves one call. REQUIRES_NEW like QuotaGovernor.tryAcquire: once the request
     * is sent the call is used, even if the caller's transaction rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(String jobName, Priority priority) {
        AlphaVantageQuotaBudget budget = currentBudget();
        int remaining = budget.remaining();

        if (remaining - 1 < priority.floor()) {
            log.warn("[AV-QUOTA] denied job={} priority={} remaining={} floor={} upstreamExhausted={}",
                    jobName, priority, remaining, priority.floor(), budget.isUpstreamExhausted());
            publishGauges(budget);
            return false;
        }

        budget.spend(1);
        budgets.save(budget);
        publishGauges(budget);
        log.debug("[AV-QUOTA] granted job={} remaining={}", jobName, budget.remaining());
        return true;
    }

    /** Logs a finished call in api_call_log. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(String jobName, String function, Integer statusCode, long durationMs) {
        AlphaVantageQuotaBudget budget = currentBudget();
        jdbc.update("""
                        insert into api_call_log
                            (quota_date, job_name, endpoint, status_code, duration_ms, remaining_after)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                today(), jobName, "alphavantage:" + function, statusCode, (int) durationMs,
                budget.remaining());
    }

    /** The API answered with a rate-limit message. Trust it and stop until the UTC reset. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUpstreamRefusal(String jobName) {
        AlphaVantageQuotaBudget budget = currentBudget();
        if (!budget.isUpstreamExhausted()) {
            log.warn("[AV-QUOTA] upstream refused job={} with our count at {}/{} — "
                    + "treating today as exhausted", jobName, budget.getConsumed(), budget.getDailyLimit());
        }
        budget.markUpstreamExhausted();
        budgets.save(budget);
        publishGauges(budget);
    }

    private AlphaVantageQuotaBudget currentBudget() {
        LocalDate today = today();
        return budgets.findForUpdate(today)
                .orElseGet(() -> budgets.saveAndFlush(new AlphaVantageQuotaBudget(today, dailyLimit)));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private void publishGauges(AlphaVantageQuotaBudget budget) {
        remainingGauge.set(budget.remaining());
        consumedGauge.set(budget.getConsumed());
    }
}
