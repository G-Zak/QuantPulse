package com.quantpulse.marketdata.quota;

import com.quantpulse.marketdata.domain.QuotaBudget;
import com.quantpulse.marketdata.repository.QuotaBudgetRepository;
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
 * Keeps us under Drahmi's limit of 100 calls per day.
 *
 * If we run out we get no data until midnight UTC, so retrying doesn't help.
 * The only option is not to spend the call. So the budget is saved in the database
 * and checked before every call.
 *
 * Each job has a priority, and each priority stops at a floor:
 *
 *   remaining  100  all jobs run
 *               35  BACKFILL stops
 *               15  NORMAL stops
 *                5  HIGH stops
 *                0  CRITICAL can use the last call
 *
 * That way a long backfill can't use the calls the market snapshot needs.
 *
 * Every response has X-RateLimit-Remaining. If it's lower than our count we trust it
 * (someone else may be using the key).
 */
@Service
public class QuotaGovernor {

    private static final Logger log = LoggerFactory.getLogger(QuotaGovernor.class);

    /** Calls that must be left before a priority can't spend anymore. */
    public enum JobPriority {
        /** Health/status checks. Can use the last call. */
        CRITICAL(0),
        /** Index refresh. */
        HIGH(5),
        /** Market snapshot, detail lookups. */
        NORMAL(15),
        /** History backfill. Can wait, only runs with lots of calls left. */
        BACKFILL(35);

        private final int floor;

        JobPriority(int floor) {
            this.floor = floor;
        }

        public int floor() {
            return floor;
        }
    }

    private final QuotaBudgetRepository budgets;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int dailyLimit;

    /** Copy for Micrometer, so the gauge doesn't query the DB. */
    private final AtomicInteger remainingGauge = new AtomicInteger();
    private final AtomicInteger consumedGauge = new AtomicInteger();

    public QuotaGovernor(QuotaBudgetRepository budgets,
                         JdbcTemplate jdbc,
                         MeterRegistry meterRegistry,
                         Clock clock,
                         @Value("${quantpulse.quota.daily-limit:100}") int dailyLimit) {
        this.budgets = budgets;
        this.jdbc = jdbc;
        this.clock = clock;
        this.dailyLimit = dailyLimit;

        Gauge.builder("quantpulse.quota.remaining", remainingGauge, AtomicInteger::get)
                .description("Upstream API calls still available today")
                .register(meterRegistry);
        Gauge.builder("quantpulse.quota.consumed", consumedGauge, AtomicInteger::get)
                .description("Upstream API calls spent today")
                .register(meterRegistry);
    }

    /**
     * Loads the gauges from the DB at startup and re-syncs them regularly.
     *
     * Otherwise they show 0 until the first API call, and in dev (fixtures, no calls)
     * the dashboard would say "0 calls left" forever. The re-sync also picks up the
     * midnight reset.
     */
    @jakarta.annotation.PostConstruct
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${quantpulse.quota.gauge-refresh-ms:60000}")
    @Transactional(readOnly = true)
    public void refreshGauges() {
        publishGauges(budgets.findById(today())
                .orElseGet(() -> new QuotaBudget(today(), dailyLimit)));
    }

    /**
     * Tries to reserve cost calls for a job.
     *
     * REQUIRES_NEW so the spend is saved even if the caller rolls back: once the request
     * is sent the API has counted it. Otherwise our count and the API's would drift.
     *
     * @return true if the caller can make the request
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(String jobName, JobPriority priority, int cost) {
        QuotaBudget budget = currentBudget();
        int remaining = budget.remaining();

        if (remaining - cost < priority.floor()) {
            log.warn("[QUOTA] denied job={} priority={} cost={} remaining={} floor={}",
                    jobName, priority, cost, remaining, priority.floor());
            publishGauges(budget);
            return false;
        }

        budget.spend(cost);
        budgets.save(budget);
        publishGauges(budget);
        log.debug("[QUOTA] granted job={} cost={} remaining={}", jobName, cost, budget.remaining());
        return true;
    }

    /**
     * Logs the call result and syncs with the API's own counter.
     * Own transaction too, so the log row is kept if the caller fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(String jobName, String endpoint, Integer statusCode,
                              long durationMs, Integer upstreamRemaining) {
        QuotaBudget budget = currentBudget();
        int before = budget.getConsumed();
        budget.reconcile(upstreamRemaining);
        if (budget.getConsumed() != before) {
            log.warn("[QUOTA] reconciled against upstream: consumed {} -> {} (upstream remaining={})",
                    before, budget.getConsumed(), upstreamRemaining);
        }
        budgets.save(budget);
        publishGauges(budget);

        jdbc.update("""
                        insert into api_call_log
                            (quota_date, job_name, endpoint, status_code, duration_ms, remaining_after)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                today(), jobName, endpoint, statusCode, (int) durationMs, upstreamRemaining);
    }

    /** Gives a call back when the request was never sent (e.g. cache hit). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refund(String jobName, int cost) {
        QuotaBudget budget = currentBudget();
        budget.spend(-Math.min(cost, budget.getConsumed()));
        budgets.save(budget);
        publishGauges(budget);
        log.debug("[QUOTA] refunded job={} cost={} remaining={}", jobName, cost, budget.remaining());
    }

    @Transactional(readOnly = true)
    public QuotaSnapshot snapshot() {
        QuotaBudget b = budgets.findById(today())
                .orElseGet(() -> new QuotaBudget(today(), dailyLimit));
        return new QuotaSnapshot(b.getQuotaDate(), b.getDailyLimit(), b.getConsumed(),
                b.remaining(), b.getUpstreamRemaining());
    }

    /**
     * Loads today's budget, creates it if needed.
     * findForUpdate locks the row so two callers can't both see "1 left" and both spend it.
     */
    private QuotaBudget currentBudget() {
        LocalDate today = today();
        return budgets.findForUpdate(today)
                .orElseGet(() -> budgets.saveAndFlush(new QuotaBudget(today, dailyLimit)));
    }

    /** UTC, the API resets at midnight UTC. */
    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private void publishGauges(QuotaBudget budget) {
        remainingGauge.set(budget.remaining());
        consumedGauge.set(budget.getConsumed());
    }

    public record QuotaSnapshot(LocalDate date, int dailyLimit, int consumed,
                                int remaining, Integer upstreamRemaining) {
    }
}
