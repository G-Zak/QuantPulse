package com.quantpulse.marketdata.ingestion;

import com.quantpulse.marketdata.quota.QuotaGovernor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Decides when we spend Drahmi API calls (100 per day).
 *
 *   /market/status      2 x daily                          =  2 calls
 *   /stocks?limit=200   every 15 min, 6h session, 5 d/wk   = 24 calls   (all 81 instruments)
 *   /indices            every 15 min during session        = 24 calls
 *   /sectors            once daily                         =  1 call
 *   history backfill    3 per run, once daily              =  3 calls
 *                                                     total ~ 54, ~46 left
 *
 * Every 15 min because the data itself is ~15 min delayed, polling faster gets nothing new.
 * @SchedulerLock so two instances don't both run and burn the budget.
 */
@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final MarketIngestionService ingestion;
    private final HistoryBackfillService backfill;
    private final TradingCalendar calendar;
    private final QuotaGovernor quota;
    private final FxRateIngestionService fx;
    private final Week52RangeService week52;
    private final BenchmarkIngestionService benchmarks;

    public IngestionScheduler(MarketIngestionService ingestion,
                              HistoryBackfillService backfill,
                              TradingCalendar calendar,
                              QuotaGovernor quota,
                              FxRateIngestionService fx,
                              Week52RangeService week52,
                              BenchmarkIngestionService benchmarks) {
        this.ingestion = ingestion;
        this.backfill = backfill;
        this.calendar = calendar;
        this.quota = quota;
        this.fx = fx;
        this.week52 = week52;
        this.benchmarks = benchmarks;
    }

    /** Main loop. Runs every 15 minutes but returns right away when the market is closed. */
    @Scheduled(fixedDelayString = "${quantpulse.ingestion.snapshot-ms:900000}",
               initialDelayString = "${quantpulse.ingestion.initial-delay-ms:5000}")
    @SchedulerLock(name = "ingest-snapshot", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void ingestSnapshot() {
        if (!calendar.isTradingNow()) {
            log.debug("[SCHED] market closed ({}), skipping snapshot",
                    calendar.nowInCasablanca().toLocalTime());
            return;
        }
        ingestion.ingestMarketSnapshot();
        ingestion.ingestIndices();
    }

    /** One snapshot right after the close to get the final closing prices. */
    @Scheduled(cron = "${quantpulse.ingestion.close-cron:0 45 15 * * MON-FRI}",
               zone = "Africa/Casablanca")
    @SchedulerLock(name = "ingest-close", lockAtMostFor = "PT10M")
    public void ingestClosingSnapshot() {
        if (!calendar.isTradingDay(calendar.nowInCasablanca().toLocalDate())) {
            return;
        }
        log.info("[SCHED] closing snapshot");
        ingestion.ingestMarketSnapshot();
        ingestion.ingestIndices();
    }

    /**
     * Night jobs. Sectors only change on a listing, so once a day is enough. Backfill runs
     * here because it needs 35+ calls left, and the budget is full right after the UTC reset.
     */
    @Scheduled(cron = "${quantpulse.ingestion.nightly-cron:0 15 1 * * *}", zone = "UTC")
    @SchedulerLock(name = "ingest-nightly", lockAtMostFor = "PT30M")
    public void nightly() {
        var snapshot = quota.snapshot();
        log.info("[SCHED] nightly job starting — quota {}/{} remaining",
                snapshot.remaining(), snapshot.dailyLimit());

        ingestion.ingestSectors();
        ingestion.harvestMarketCaps();

        long pending = backfill.pendingCount();
        if (pending > 0) {
            int done = backfill.runNextBatch();
            log.info("[SCHED] backfill: {} completed, {} still pending", done, pending - done);
        }
        week52.refresh();
    }

    /**
     * Daily Alpha Vantage job. Separate provider with its own 25/day limit, so it doesn't
     * use Drahmi calls. Spot FX for 4 pairs, FX history while it's short, one series per
     * benchmark: about 6 calls a day. Runs on weekends too, simpler than handling calendars,
     * and "latest on or before" makes a missed day harmless.
     */
    @Scheduled(cron = "${quantpulse.alphavantage.fx-cron:0 30 1 * * *}", zone = "UTC")
    @SchedulerLock(name = "fx-refresh", lockAtMostFor = "PT10M")
    public void refreshFxRates() {
        fx.refreshRates();
        fx.backfillHistory();
        benchmarks.refresh();
    }
}
