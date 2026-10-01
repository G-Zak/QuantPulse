package com.quantpulse.marketdata.benchmark;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;

/**
 * The math for "Casablanca vs the world". No I/O, so it's easy to unit test.
 *
 * - Dates follow the Casablanca trading calendar (the user is a MAD investor).
 * - When New York or FX has no value for a day, the last known value is used.
 * - Each day is converted at that day's rate, not today's, otherwise the currency
 *   effect disappears.
 * - All series start at 100 on the first date they all have data.
 */
public final class BenchmarkComparison {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private BenchmarkComparison() {
    }

    public record Input(String indexCode, String indexLabel, NavigableMap<LocalDate, BigDecimal> index,
                        Map<String, Benchmark> benchmarks, NavigableMap<LocalDate, BigDecimal> usdMad,
                        LocalDate start, LocalDate end, String rangeLabel) {
    }

    /** A benchmark priced in USD. */
    public record Benchmark(String label, NavigableMap<LocalDate, BigDecimal> usdCloses) {
    }

    public record Point(LocalDate date, Map<String, BigDecimal> values) {
    }

    public record SeriesSummary(String code, String label, String currency,
                                BigDecimal returnMadPercent, BigDecimal returnLocalPercent) {
    }

    public record Result(LocalDate start, LocalDate end, List<Point> points, List<SeriesSummary> series,
                         BigDecimal usdMadChangePercent, String verdict) {
    }

    public static Result compute(Input in) {
        List<LocalDate> axis = new ArrayList<>(in.index().subMap(in.start(), true, in.end(), true).keySet());

        // MAD value of each series for each date, null if a series has no data yet.
        Map<String, Map<LocalDate, BigDecimal>> mad = new LinkedHashMap<>();
        Map<String, Map<LocalDate, BigDecimal>> local = new LinkedHashMap<>();
        mad.put(in.indexCode(), new LinkedHashMap<>());
        for (String code : in.benchmarks().keySet()) {
            mad.put(code, new LinkedHashMap<>());
            local.put(code, new LinkedHashMap<>());
        }
        for (LocalDate d : axis) {
            mad.get(in.indexCode()).put(d, in.index().get(d));
            BigDecimal rate = floor(in.usdMad(), d);
            for (var b : in.benchmarks().entrySet()) {
                BigDecimal usd = floor(b.getValue().usdCloses(), d);
                local.get(b.getKey()).put(d, usd);
                mad.get(b.getKey()).put(d, usd == null || rate == null ? null : usd.multiply(rate, MC));
            }
        }

        LocalDate base = axis.stream()
                .filter(d -> mad.values().stream().allMatch(s -> s.get(d) != null))
                .findFirst().orElse(null);
        if (base == null) {
            return new Result(in.start(), in.end(), List.of(), List.of(), null,
                    "Not enough overlapping data to compare yet.");
        }
        List<LocalDate> window = axis.subList(axis.indexOf(base), axis.size());
        LocalDate last = window.get(window.size() - 1);

        List<Point> points = new ArrayList<>();
        for (LocalDate d : window) {
            Map<String, BigDecimal> values = new LinkedHashMap<>();
            for (var s : mad.entrySet()) {
                values.put(s.getKey(), rebase(s.getValue().get(d), s.getValue().get(base)));
            }
            points.add(new Point(d, values));
        }

        List<SeriesSummary> series = new ArrayList<>();
        series.add(new SeriesSummary(in.indexCode(), in.indexLabel(), "MAD",
                pct(mad.get(in.indexCode()).get(last), mad.get(in.indexCode()).get(base)), null));
        for (var b : in.benchmarks().entrySet()) {
            String code = b.getKey();
            series.add(new SeriesSummary(code, b.getValue().label(), "USD",
                    pct(mad.get(code).get(last), mad.get(code).get(base)),
                    pct(local.get(code).get(last), local.get(code).get(base))));
        }
        BigDecimal fx = pct(floor(in.usdMad(), last), floor(in.usdMad(), base));

        return new Result(base, last, points, series, fx, verdict(series, fx, in.rangeLabel(), last));
    }

    /**
     * One sentence built from the numbers (not AI). Example:
     * "Over 1 year to 31 Jul 2026, in dirhams: MASI +12.3%, S&P 500 +8.1%, Emerging markets +4.0%.
     * MASI led. The dirham strengthened 3.1% against the dollar, which took about 3.2 points
     * off the US-listed funds' returns for a MAD investor."
     */
    static String verdict(List<SeriesSummary> series, BigDecimal usdMadChange, String rangeLabel, LocalDate end) {
        StringBuilder sb = new StringBuilder("Over ").append(rangeLabel).append(" to ")
                .append(end.format(DAY)).append(", in dirhams: ");
        for (int i = 0; i < series.size(); i++) {
            SeriesSummary s = series.get(i);
            sb.append(i == 0 ? "" : ", ").append(s.label()).append(' ').append(signed(s.returnMadPercent())).append('%');
        }
        SeriesSummary leader = series.stream()
                .max(Comparator.comparing(SeriesSummary::returnMadPercent)).orElseThrow();
        sb.append(". ").append(leader.label()).append(" led.");

        if (usdMadChange != null && usdMadChange.signum() != 0) {
            // USD/MAD going down means the dirham got stronger.
            boolean dirhamStronger = usdMadChange.signum() < 0;
            BigDecimal drag = series.stream().filter(s -> s.returnLocalPercent() != null)
                    .map(s -> s.returnMadPercent().subtract(s.returnLocalPercent()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(Math.max(1, series.size() - 1)), 1, RoundingMode.HALF_EVEN);
            sb.append(" The dirham ").append(dirhamStronger ? "strengthened " : "weakened ")
                    .append(usdMadChange.abs().setScale(1, RoundingMode.HALF_EVEN))
                    .append("% against the dollar, which ")
                    .append(drag.signum() < 0 ? "took about " : "added about ")
                    .append(drag.abs()).append(drag.signum() < 0 ? " points off" : " points to")
                    .append(" the US-listed funds' returns for a MAD investor.");
        }
        return sb.toString();
    }

    private static BigDecimal floor(NavigableMap<LocalDate, BigDecimal> series, LocalDate d) {
        Map.Entry<LocalDate, BigDecimal> e = series.floorEntry(d);
        return e == null ? null : e.getValue();
    }

    private static BigDecimal rebase(BigDecimal v, BigDecimal base) {
        return v.multiply(HUNDRED, MC).divide(base, 2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal pct(BigDecimal last, BigDecimal first) {
        if (last == null || first == null || first.signum() == 0) {
            return null;
        }
        return last.divide(first, MC).subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(2, RoundingMode.HALF_EVEN);
    }

    private static String signed(BigDecimal v) {
        BigDecimal r = v.setScale(1, RoundingMode.HALF_EVEN);
        return (r.signum() >= 0 ? "+" : "") + r.toPlainString();
    }
}
