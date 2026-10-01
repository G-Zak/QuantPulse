package com.quantpulse.marketdata.ingestion;

import com.quantpulse.common.money.Money;
import com.quantpulse.marketdata.domain.BenchmarkBar;
import com.quantpulse.marketdata.repository.BenchmarkBarRepository;
import com.quantpulse.marketdata.upstream.AlphaVantageClient;
import com.quantpulse.marketdata.upstream.AlphaVantageDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Saves daily closes of the world benchmarks (SPY, EEM) in benchmark_history.
 *
 * One call per symbol per day. Until we have a year stored we ask for the full history.
 * The free tier may refuse that (premium), then we fall back to the last ~100 days.
 */
@Service
public class BenchmarkIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkIngestionService.class);
    private static final int FULL_HISTORY_ROWS = 250;

    private final AlphaVantageClient client;
    private final BenchmarkBarRepository bars;
    private final List<String> symbols;

    public BenchmarkIngestionService(AlphaVantageClient client, BenchmarkBarRepository bars,
                                     @Value("${quantpulse.alphavantage.benchmarks:SPY,EEM}") List<String> symbols) {
        this.client = client;
        this.bars = bars;
        this.symbols = symbols;
    }

    public List<String> symbols() {
        return symbols;
    }

    /** @return new rows saved */
    @Transactional
    public int refresh() {
        int inserted = 0;
        for (String raw : symbols) {
            String symbol = raw.trim().toUpperCase();
            String source = client.sourceFor("daily-" + symbol);
            boolean real = !"SYNTHETIC".equals(source);
            // Once real data is available, synthetic rows are treated as missing and get replaced.
            long have = real ? bars.countBySymbolAndSourceNot(symbol, "SYNTHETIC") : bars.countBySymbol(symbol);
            boolean wantFull = have < FULL_HISTORY_ROWS;
            List<AlphaVantageDtos.OhlcvPointDto> points = client.fetchDailySeries(symbol, wantFull);
            if (points.isEmpty() && wantFull) {
                points = client.fetchDailySeries(symbol, false);
            }
            if (points.isEmpty()) {
                log.warn("[BENCH] no data for {} this run; stored history unchanged", symbol);
                continue;
            }
            Set<LocalDate> stored = real ? bars.findNonSyntheticDates(symbol) : bars.findStoredDates(symbol);
            List<BenchmarkBar> fresh = new ArrayList<>();
            for (AlphaVantageDtos.OhlcvPointDto p : points) {
                if (p.close() != null && p.close().signum() > 0 && !stored.contains(p.date())) {
                    // US ETFs, priced in USD.
                    fresh.add(new BenchmarkBar(symbol, p.date(),
                            p.close().setScale(Money.SCALE, Money.ROUNDING), "USD", source));
                }
            }
            bars.saveAll(fresh);
            inserted += fresh.size();
            log.info("[BENCH] {}: {} new sessions ({})", symbol, fresh.size(), source);
        }
        return inserted;
    }
}
