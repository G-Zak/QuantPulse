package com.quantpulse.marketdata.ingestion;

import com.quantpulse.common.event.OhlcvBarEvent;
import com.quantpulse.common.event.Topology;
import com.quantpulse.marketdata.domain.BackfillTask;
import com.quantpulse.marketdata.domain.Instrument;
import com.quantpulse.marketdata.outbox.OutboxWriter;
import com.quantpulse.marketdata.repository.InstrumentRepository;
import com.quantpulse.marketdata.repository.BackfillTaskRepository;
import com.quantpulse.marketdata.repository.DividendRepository;
import com.quantpulse.marketdata.repository.IndexHistoryRepository;
import com.quantpulse.marketdata.upstream.DrahmiDtos;
import com.quantpulse.marketdata.upstream.MarketDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Fetches OHLCV history a few instruments at a time.
 *
 * A year of bars for 81 instruments is 81 calls, most of the daily quota. A simple
 * loop would run out halfway with no record of what worked. Each fetch is a saved task
 * instead: spread over days, survives restarts, and BACKFILL priority only spends
 * while 35+ calls are left so the live snapshot always has calls.
 */
@Service
public class HistoryBackfillService {

    private static final Logger log = LoggerFactory.getLogger(HistoryBackfillService.class);

    private final MarketDataProvider provider;
    private final BackfillTaskRepository tasks;
    private final InstrumentRepository instruments;
    private final IndexHistoryRepository indexHistory;
    private final DividendRepository dividends;
    private final OhlcvBatchWriter batchWriter;
    private final OutboxWriter outbox;
    private final int perRun;

    /** Proxy to this bean, see runNextBatch(). */
    private final HistoryBackfillService self;

    public HistoryBackfillService(MarketDataProvider provider,
                                  BackfillTaskRepository tasks,
                                  InstrumentRepository instruments,
                                  IndexHistoryRepository indexHistory,
                                  DividendRepository dividends,
                                  OhlcvBatchWriter batchWriter,
                                  OutboxWriter outbox,
                                  @Lazy HistoryBackfillService self,
                                  @Value("${quantpulse.backfill.per-run:3}") int perRun) {
        this.self = self;
        this.provider = provider;
        this.tasks = tasks;
        this.instruments = instruments;
        this.indexHistory = indexHistory;
        this.dividends = dividends;
        this.batchWriter = batchWriter;
        this.outbox = outbox;
        this.perRun = perRun;
    }

    /** One task per active instrument, ordered by market cap so big names get history first. */
    @Transactional
    public int seedTasks(String range) {
        List<Instrument> active = instruments.findByActiveTrueOrderByMarketCapDesc();
        int[] rank = {active.size()};
        int created = 0;

        for (Instrument instrument : active.stream()
                .sorted(Comparator.comparing(
                        (Instrument i) -> i.getMarketCap() == null ? BigDecimal.ZERO : i.getMarketCap())
                        .reversed())
                .toList()) {
            int priority = rank[0]--;
            if (tasks.findByTickerAndRangeCodeAndKind(
                    instrument.getTicker(), range, BackfillTask.Kind.OHLCV).isEmpty()) {
                tasks.save(new BackfillTask(instrument.getTicker(), range, priority,
                        BackfillTask.Kind.OHLCV));
                created++;
            }
        }
        log.info("[BACKFILL] seeded {} new tasks (range={})", created, range);
        return created;
    }

    /**
     * Runs the next few pending tasks.
     *
     * Each task has its own transaction, so a failure on the third doesn't undo the first two
     * (those calls are already spent).
     *
     * We call runOne through self, not this: @Transactional works through a Spring proxy,
     * and this.runOne() skips it, so no transaction. OutboxWriter.append is MANDATORY,
     * so that mistake throws. @Lazy avoids the circular dependency at startup.
     */
    public int runNextBatch() {
        return runNextBatch(BackfillTask.Kind.OHLCV);
    }

    public int runNextBatch(BackfillTask.Kind kind) {
        List<BackfillTask> batch = tasks.findNextPending(kind, PageRequest.of(0, perRun));
        int completed = 0;
        for (BackfillTask task : batch) {
            if (self.runOne(task.getId())) {
                completed++;
            }
        }
        return completed;
    }

    @Transactional
    public boolean runOne(Long taskId) {
        BackfillTask task = tasks.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != BackfillTask.Status.PENDING) {
            return false;
        }
        task.markRunning();

        if (task.getKind() == BackfillTask.Kind.DIVIDEND) {
            return runDividendTask(task);
        }
        if (task.getKind() == BackfillTask.Kind.FUNDAMENTALS) {
            return runFundamentalsTask(task);
        }

        try {
            List<DrahmiDtos.OhlcvPoint> points =
                    provider.fetchHistory(task.getTicker(), task.getRangeCode());
            if (points.isEmpty()) {
                // Empty means no quota, circuit open, or unknown ticker. Try again later.
                task.markFailed("no points returned");
                tasks.save(task);
                return false;
            }

            int written = batchWriter.upsertAll(task.getTicker(), points);

            // One event per new bar so qp-portfolio can update risk.
            // Only new rows, so re-running doesn't resend a year of events.
            if (written > 0) {
                for (DrahmiDtos.OhlcvPoint p : points) {
                    if (p.date() == null || p.close() == null) {
                        continue;
                    }
                    outbox.append("OhlcvBar", task.getTicker(),
                            Topology.routingKey(Topology.RK_OHLCV_BAR, task.getTicker()),
                            OhlcvBarEvent.of(task.getTicker(), p.date(), p.open(), p.high(),
                                    p.low(), p.close(),
                                    p.volume() == null ? 0L : p.volume().longValue()));
                }
            }

            task.markDone();
            tasks.save(task);
            log.info("[BACKFILL] {} -> {} bars ({} new)", task.getTicker(), points.size(), written);
            return true;

        } catch (Exception e) {
            task.markFailed(e.toString());
            tasks.save(task);
            log.warn("[BACKFILL] {} failed (attempt {}): {}",
                    task.getTicker(), task.getAttempts(), e.getMessage());
            return false;
        }
    }

    /** One dividend task per active instrument, biggest market cap first. */
    @Transactional
    public int seedDividendTasks() {
        int created = 0;
        List<Instrument> active = instruments.findByActiveTrueOrderByMarketCapDesc();
        int rank = active.size();
        for (Instrument instrument : active) {
            if (tasks.findByTickerAndRangeCodeAndKind(
                    instrument.getTicker(), "ALL", BackfillTask.Kind.DIVIDEND).isEmpty()) {
                tasks.save(new BackfillTask(instrument.getTicker(), "ALL", rank,
                        BackfillTask.Kind.DIVIDEND));
                created++;
            }
            rank--;
        }
        log.info("[BACKFILL] seeded {} dividend tasks", created);
        return created;
    }

    /**
     * One fundamentals task per instrument.
     *
     * Calls /stocks/{ticker} for P/E, dividend yield, ISIN and the 52-week range (not in
     * the bulk listing). 81 calls total, spread over the queue.
     */
    @Transactional
    public int seedFundamentalsTasks() {
        int created = 0;
        List<Instrument> active = instruments.findByActiveTrueOrderByMarketCapDesc();
        int rank = active.size();
        for (Instrument instrument : active) {
            if (tasks.findByTickerAndRangeCodeAndKind(
                    instrument.getTicker(), "NA", BackfillTask.Kind.FUNDAMENTALS).isEmpty()) {
                tasks.save(new BackfillTask(instrument.getTicker(), "NA", rank,
                        BackfillTask.Kind.FUNDAMENTALS));
                created++;
            }
            rank--;
        }
        log.info("[BACKFILL] seeded {} fundamentals tasks", created);
        return created;
    }

    private boolean runFundamentalsTask(BackfillTask task) {
        try {
            var detail = provider.fetchStockDetail(task.getTicker());
            if (detail.isEmpty()) {
                task.markFailed("no detail returned");
                tasks.save(task);
                return false;
            }
            var d = detail.get();
            Instrument instrument = instruments.findByTicker(task.getTicker()).orElse(null);
            if (instrument == null) {
                task.markFailed("instrument not found locally");
                tasks.save(task);
                return false;
            }
            instrument.applyFundamentals(d.marketCap(), d.dividendYield(), d.peRatio(),
                    d.week52High(), d.week52Low(), d.isin());
            instruments.save(instrument);
            task.markDone();
            tasks.save(task);
            log.info("[BACKFILL] fundamentals {} -> PE={} yield={}",
                    task.getTicker(), d.peRatio(), d.dividendYield());
            return true;
        } catch (Exception e) {
            task.markFailed(e.toString());
            tasks.save(task);
            log.warn("[BACKFILL] fundamentals {} failed: {}", task.getTicker(), e.getMessage());
            return false;
        }
    }

    private boolean runDividendTask(BackfillTask task) {
        try {
            List<DrahmiDtos.DividendDto> rows = provider.fetchDividends(task.getTicker());
            int written = 0;
            for (DrahmiDtos.DividendDto d : rows) {
                if (d.exDate() == null || d.amount() == null || d.amount().signum() <= 0) {
                    continue;
                }
                // Key is (ticker, exDate), so re-running doesn't duplicate.
                if (dividends.findByTickerAndExDate(task.getTicker(), d.exDate()).isPresent()) {
                    continue;
                }
                dividends.save(new com.quantpulse.marketdata.domain.Dividend(
                        task.getTicker(), d.exDate(), d.paymentDate(), d.amount(), d.currency()));
                written++;
            }
            // Empty is normal here (lots of stocks don't pay dividends), so it's DONE, not a retry.
            task.markDone();
            tasks.save(task);
            log.info("[BACKFILL] dividends {} -> {} declared ({} new)",
                    task.getTicker(), rows.size(), written);
            return true;
        } catch (Exception e) {
            task.markFailed(e.toString());
            tasks.save(task);
            log.warn("[BACKFILL] dividends {} failed: {}", task.getTicker(), e.getMessage());
            return false;
        }
    }

    /** Index history is the benchmark for beta/alpha, fetched separately. */
    @Transactional
    public int backfillIndex(String code, String range) {
        List<DrahmiDtos.IndexHistoryPoint> points = provider.fetchIndexHistory(code, range);
        int written = 0;
        for (DrahmiDtos.IndexHistoryPoint p : points) {
            if (p.date() == null || p.value() == null) {
                continue;
            }
            var key = new com.quantpulse.marketdata.domain.IndexHistory.Key(code, p.date());
            if (indexHistory.existsById(key)) {
                continue;
            }
            indexHistory.save(new com.quantpulse.marketdata.domain.IndexHistory(
                    code, p.date(), p.value(), p.changePercent(), p.changeValue()));
            written++;
        }
        log.info("[BACKFILL] index {} -> {} points ({} new)", code, points.size(), written);
        return written;
    }

    public long pendingCount() {
        return tasks.countByStatus(BackfillTask.Status.PENDING);
    }

    public long pendingCount(BackfillTask.Kind kind) {
        return tasks.countByStatusAndKind(BackfillTask.Status.PENDING, kind);
    }
}
