package com.quantpulse.marketdata.ingestion;

import com.quantpulse.marketdata.repository.BenchmarkBarRepository;
import com.quantpulse.marketdata.repository.FxRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Loads the Alpha Vantage fixtures at startup if the tables are empty. Not in live mode.
 *
 * Otherwise a new database shows empty FX cards until the 01:30 job.
 * In live mode it would spend real calls, so only the nightly job fetches.
 */
@Component
@Profile("!live")
public class FixtureDataBootstrap {

    private static final Logger log = LoggerFactory.getLogger(FixtureDataBootstrap.class);

    private final FxRateRepository rates;
    private final BenchmarkBarRepository benchmarkBars;
    private final FxRateIngestionService fx;
    private final BenchmarkIngestionService benchmarks;

    public FixtureDataBootstrap(FxRateRepository rates, BenchmarkBarRepository benchmarkBars,
                                FxRateIngestionService fx, BenchmarkIngestionService benchmarks) {
        this.rates = rates;
        this.benchmarkBars = benchmarkBars;
        this.fx = fx;
        this.benchmarks = benchmarks;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadIfEmpty() {
        if (rates.count() < 50) {
            log.info("[BOOTSTRAP] FX rates: {} spot, {} history rows from fixtures",
                    fx.refreshRates(), fx.backfillHistory());
        }
        if (benchmarkBars.count() == 0) {
            log.info("[BOOTSTRAP] benchmarks: {} sessions from fixtures", benchmarks.refresh());
        }
    }
}
