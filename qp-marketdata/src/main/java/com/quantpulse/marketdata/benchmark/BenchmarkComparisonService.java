package com.quantpulse.marketdata.benchmark;

import com.quantpulse.marketdata.domain.BenchmarkBar;
import com.quantpulse.marketdata.domain.FxRate;
import com.quantpulse.marketdata.domain.IndexHistory;
import com.quantpulse.marketdata.repository.BenchmarkBarRepository;
import com.quantpulse.marketdata.repository.FxRateRepository;
import com.quantpulse.marketdata.repository.IndexHistoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads the series and passes them to BenchmarkComparison.
 *
 * The window ends on the last date all series have, not today. Otherwise if MASI stops
 * in July and SPY goes to September, MASI would get two flat months.
 *
 * No API calls, it only reads what the nightly jobs stored.
 */
@Service
public class BenchmarkComparisonService {

    public static final String INDEX = "MASI";
    private static final Map<String, String> LABELS = Map.of(
            "MASI", "MASI", "SPY", "S&P 500 (SPY)", "EEM", "Emerging markets (EEM)");

    private final IndexHistoryRepository indexHistory;
    private final BenchmarkBarRepository benchmarks;
    private final FxRateRepository rates;
    private final List<String> symbols;
    private final Clock clock;

    public BenchmarkComparisonService(IndexHistoryRepository indexHistory, BenchmarkBarRepository benchmarks,
                                      FxRateRepository rates,
                                      @Value("${quantpulse.alphavantage.benchmarks:SPY,EEM}") List<String> symbols,
                                      Clock clock) {
        this.indexHistory = indexHistory;
        this.benchmarks = benchmarks;
        this.rates = rates;
        this.symbols = symbols.stream().map(s -> s.trim().toUpperCase()).toList();
        this.clock = clock;
    }

    public record SourceInfo(String series, Set<String> sources) {
    }

    public record ComparisonView(String range, LocalDate start, LocalDate end,
                                 List<BenchmarkComparison.Point> points,
                                 List<BenchmarkComparison.SeriesSummary> series,
                                 BigDecimal usdMadChangePercent, String verdict,
                                 List<SourceInfo> sources, boolean sampleData, List<String> notes) {
    }

    @Transactional(readOnly = true)
    public ComparisonView compare(String range) {
        String code = range == null ? "1Y" : range.toUpperCase();
        LocalDate today = LocalDate.now(clock);
        LocalDate lookback = today.minusYears(2);

        NavigableMap<LocalDate, BigDecimal> masi = indexHistory.findSeries(INDEX, lookback, today).stream()
                .collect(Collectors.toMap(IndexHistory::getSessionDate, IndexHistory::getValue,
                        (a, b) -> b, TreeMap::new));

        Map<String, List<BenchmarkBar>> bars = new LinkedHashMap<>();
        for (String s : symbols) {
            bars.put(s, benchmarks.findBySymbolAndSessionDateBetweenOrderBySessionDateAsc(s, lookback, today));
        }
        List<FxRate> fx = rates.findByBaseCurrencyAndQuoteCurrencyAndRateDateBetweenOrderByRateDateAsc(
                "USD", "MAD", lookback, today);

        LocalDate end = Stream.concat(
                        Stream.of(masi.isEmpty() ? null : masi.lastKey(),
                                fx.isEmpty() ? null : fx.get(fx.size() - 1).getRateDate()),
                        bars.values().stream().map(l -> l.isEmpty() ? null : l.get(l.size() - 1).getSessionDate()))
                .reduce((a, b) -> a == null || b == null ? null : (a.isBefore(b) ? a : b))
                .orElse(null);

        List<SourceInfo> sources = sources(bars, fx);
        boolean sample = sources.stream().anyMatch(si -> si.sources().contains("SYNTHETIC"));
        if (end == null) {
            return new ComparisonView(code, null, null, List.of(), List.of(), null,
                    "No data yet: MASI history, benchmark history and USD/MAD rates are all needed.",
                    sources, sample, List.of());
        }

        LocalDate start = switch (code) {
            case "3M" -> end.minusMonths(3);
            case "6M" -> end.minusMonths(6);
            default -> end.minusYears(1);
        };
        String label = switch (code) {
            case "3M" -> "3 months";
            case "6M" -> "6 months";
            default -> "1 year";
        };

        Map<String, BenchmarkComparison.Benchmark> inputs = new LinkedHashMap<>();
        bars.forEach((s, list) -> inputs.put(s, new BenchmarkComparison.Benchmark(LABELS.getOrDefault(s, s),
                list.stream().collect(Collectors.toMap(BenchmarkBar::getSessionDate, BenchmarkBar::getClose,
                        (a, b) -> b, TreeMap::new)))));
        NavigableMap<LocalDate, BigDecimal> usdMad = fx.stream().collect(Collectors.toMap(
                FxRate::getRateDate, FxRate::getRate, (a, b) -> b, TreeMap::new));

        BenchmarkComparison.Result r = BenchmarkComparison.compute(new BenchmarkComparison.Input(
                INDEX, LABELS.get(INDEX), masi, inputs, usdMad, start, end, label));

        List<String> notes = List.of(
                "The window ends on the latest date every series has (" + end + "), not today.",
                "US funds are converted to MAD at each day's own USD/MAD rate.",
                "On days only one exchange traded, the other's last close is carried forward.",
                "Price returns only: dividends are excluded from every series.");
        return new ComparisonView(code, r.start(), r.end(), r.points(), r.series(),
                r.usdMadChangePercent(), r.verdict(), sources, sample, notes);
    }

    private static List<SourceInfo> sources(Map<String, List<BenchmarkBar>> bars, List<FxRate> fx) {
        List<SourceInfo> out = new java.util.ArrayList<>();
        out.add(new SourceInfo(INDEX, Set.of("DRAHMI")));
        bars.forEach((s, list) -> out.add(new SourceInfo(s, list.stream().map(BenchmarkBar::getSource)
                .filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new)))));
        out.add(new SourceInfo("USD/MAD", fx.stream().map(FxRate::getSource)
                .collect(Collectors.toCollection(TreeSet::new))));
        return out;
    }
}
