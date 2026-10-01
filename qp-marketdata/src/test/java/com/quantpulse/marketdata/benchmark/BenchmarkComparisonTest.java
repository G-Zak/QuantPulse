package com.quantpulse.marketdata.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkComparisonTest {

    private static final LocalDate MON = LocalDate.of(2026, 7, 6);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate WED = MON.plusDays(2);
    private static final LocalDate THU = MON.plusDays(3);

    private static NavigableMap<LocalDate, BigDecimal> series(Object... dateValue) {
        NavigableMap<LocalDate, BigDecimal> m = new TreeMap<>();
        for (int i = 0; i < dateValue.length; i += 2) {
            m.put((LocalDate) dateValue[i], new BigDecimal(dateValue[i + 1].toString()));
        }
        return m;
    }

    private static BenchmarkComparison.Result run(NavigableMap<LocalDate, BigDecimal> masi,
                                                  NavigableMap<LocalDate, BigDecimal> spy,
                                                  NavigableMap<LocalDate, BigDecimal> usdMad) {
        Map<String, BenchmarkComparison.Benchmark> b = new LinkedHashMap<>();
        b.put("SPY", new BenchmarkComparison.Benchmark("S&P 500 (SPY)", spy));
        return BenchmarkComparison.compute(new BenchmarkComparison.Input(
                "MASI", "MASI", masi, b, usdMad, MON, THU, "1 week"));
    }

    @Test
    @DisplayName("a US fund's MAD return includes the currency move, converted at each day's own rate")
    void convertsAtEachDaysRate() {
        var r = run(series(MON, 100, THU, 110),
                series(MON, 50, THU, 55),              // +10% in USD
                series(MON, 10, THU, 9));              // dirham strengthens 10%

        var spy = r.series().get(1);
        assertThat(spy.returnLocalPercent()).isEqualByComparingTo("10.00");
        // 55 x 9 / (50 x 10) - 1 = -1%: the dirham move cancelled the whole gain.
        assertThat(spy.returnMadPercent()).isEqualByComparingTo("-1.00");
        assertThat(r.usdMadChangePercent()).isEqualByComparingTo("-10.00");
    }

    @Test
    @DisplayName("the axis is Casablanca's calendar; a New York holiday carries the last close forward")
    void carriesForwardAcrossAHoliday() {
        var r = run(series(MON, 100, TUE, 101, WED, 102),
                series(MON, 50, WED, 52),              // no Tuesday bar in New York
                series(MON, 10));

        assertThat(r.points()).extracting(BenchmarkComparison.Point::date).containsExactly(MON, TUE, WED);
        assertThat(r.points().get(1).values().get("SPY")).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("every series is rebased to 100 on the first date all of them have data")
    void rebasesAtFirstCommonDate() {
        var r = run(series(MON, 17000, TUE, 17170, WED, 17340),
                series(TUE, 600, WED, 612),            // starts a day late
                series(MON, 10));

        assertThat(r.start()).isEqualTo(TUE);
        assertThat(r.points().get(0).values()).allSatisfy((k, v) -> assertThat(v).isEqualByComparingTo("100.00"));
        assertThat(r.points().get(1).values().get("SPY")).isEqualByComparingTo("102.00");
    }

    @Test
    @DisplayName("the verdict names the leader and explains the currency effect in plain numbers")
    void verdictIsComputedNotGenerated() {
        var r = run(series(MON, 100, THU, 112),
                series(MON, 50, THU, 55),
                series(MON, 10, THU, 9.7));

        assertThat(r.verdict())
                .startsWith("Over 1 week to 9 Jul 2026, in dirhams: MASI +12.0%, S&P 500 (SPY) +6.7%.")
                .contains("MASI led.")
                .contains("The dirham strengthened 3.0% against the dollar")
                .contains("took about 3.3 points off");
    }

    @Test
    @DisplayName("no overlap means an explicit message, not a chart of nothing")
    void noOverlap() {
        var r = run(series(MON, 100), series(THU, 50), series(MON, 10));
        assertThat(r.points()).isEmpty();
        assertThat(r.verdict()).contains("Not enough overlapping data");
    }
}
