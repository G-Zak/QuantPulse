package com.quantpulse.portfolio.risk;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Risk metrics from stored OHLCV, as pure functions.
 *
 * Drahmi has a risk endpoint, but we compute our own and compare. It saves a call, and
 * I want to be able to explain every number. Their ATW data even has atr14 = 152 on a
 * ~753 MAD stock that moves 5-10 MAD a day, so theirs isn't always right.
 *
 * Choices that change the result:
 * - Log returns for volatility and VaR (they add up over time, so sqrt(t) scaling works).
 * - Simple returns for performance (what the investor actually earned).
 * - sqrt(252) to annualise. The real ATW year has 245 sessions, so this is a convention
 *   and explains small differences with the vendor.
 * - Sample standard deviation (n-1).
 * - Historical VaR, no normal distribution assumed (this market has fat tails).
 *
 * double is fine here, unlike the rest of the code: these are estimates, not money,
 * and log/sqrt don't exist for BigDecimal anyway.
 */
public final class RiskCalculator {

    /** Trading days per year (convention). */
    public static final double TRADING_DAYS = 252.0;

    private RiskCalculator() {
    }

    // Returns

    /**
     * Log returns: ln(P(t) / P(t-1)).
     * Prices <= 0 are skipped, one bad row shouldn't turn everything into NaN.
     */
    public static double[] logReturns(List<BigDecimal> closes) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            double prev = closes.get(i - 1).doubleValue();
            double curr = closes.get(i).doubleValue();
            if (prev > 0 && curr > 0) {
                out.add(Math.log(curr / prev));
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Simple returns: (P(t) - P(t-1)) / P(t-1). For performance, not volatility. */
    public static double[] simpleReturns(List<BigDecimal> closes) {
        List<Double> out = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            double prev = closes.get(i - 1).doubleValue();
            double curr = closes.get(i).doubleValue();
            if (prev > 0) {
                out.add((curr - prev) / prev);
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    // Volatility

    /** Annualised volatility: sample std dev of daily log returns x sqrt(252). */
    public static double realizedVolatility(double[] logReturns) {
        return stdDev(logReturns) * Math.sqrt(TRADING_DAYS);
    }

    /**
     * Downside deviation: like volatility but only counting losses.
     * Normal volatility treats a big gain like a big loss. Used by the Sortino ratio.
     */
    public static double downsideVolatility(double[] returns) {
        if (returns.length < 2) {
            return 0.0;
        }
        // Measured from 0, not from the mean.
        //
        // Divide by the total number of returns, not just the negative ones (positive returns
        // count as 0). My first version divided by the negative count and was 43% too high
        // compared to the vendor for ATW (0.2360 vs 0.1644), exactly sqrt(n_total / n_negative).
        // Comparing with their number is how I found it.
        double sumSq = 0;
        for (double r : returns) {
            if (r < 0) {
                sumSq += r * r;
            }
        }
        return Math.sqrt(sumSq / (returns.length - 1)) * Math.sqrt(TRADING_DAYS);
    }

    // Drawdown

    /**
     * Biggest fall from a peak, as a positive fraction (0.125 = -12.5%).
     * Computed on prices because it depends on the order of the returns.
     */
    public static double maxDrawdown(List<BigDecimal> closes) {
        double peak = Double.NEGATIVE_INFINITY;
        double worst = 0.0;
        for (BigDecimal c : closes) {
            double v = c.doubleValue();
            if (v > peak) {
                peak = v;
            }
            if (peak > 0) {
                worst = Math.max(worst, (peak - v) / peak);
            }
        }
        return worst;
    }

    /** Drawdown at each point, for the chart. */
    public static double[] drawdownSeries(List<BigDecimal> closes) {
        double[] out = new double[closes.size()];
        double peak = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < closes.size(); i++) {
            double v = closes.get(i).doubleValue();
            peak = Math.max(peak, v);
            out[i] = peak > 0 ? (peak - v) / peak : 0.0;
        }
        return out;
    }

    // VaR / CVaR

    /**
     * Historical Value at Risk, as a positive loss fraction.
     *
     * VaR(95) = 0.022 means on 95% of days the loss was 2.2% or less.
     * It says nothing about the worst 5%, that's what CVaR is for.
     * Historical, so no normal distribution assumed, which would underestimate big losses.
     */
    public static double valueAtRisk(double[] returns, double confidence) {
        if (returns.length == 0) {
            return 0.0;
        }
        double[] sorted = returns.clone();
        java.util.Arrays.sort(sorted);
        int index = (int) Math.floor((1.0 - confidence) * sorted.length);
        index = Math.max(0, Math.min(index, sorted.length - 1));
        return Math.max(0.0, -sorted[index]);
    }

    /**
     * Conditional VaR (expected shortfall): the average loss on the days worse than VaR.
     * Always >= VaR.
     */
    public static double conditionalVaR(double[] returns, double confidence) {
        if (returns.length == 0) {
            return 0.0;
        }
        double[] sorted = returns.clone();
        java.util.Arrays.sort(sorted);
        int cutoff = (int) Math.floor((1.0 - confidence) * sorted.length);
        cutoff = Math.max(1, Math.min(cutoff, sorted.length));

        double sum = 0;
        for (int i = 0; i < cutoff; i++) {
            sum += sorted[i];
        }
        return Math.max(0.0, -(sum / cutoff));
    }

    // Beta / alpha

    /**
     * Beta: cov(asset, benchmark) / var(benchmark).
     *
     * 1 moves like the index, below 1 is defensive, above 1 amplifies.
     * The two series must be matched by date before calling this.
     */
    public static double beta(double[] assetReturns, double[] benchmarkReturns) {
        int n = Math.min(assetReturns.length, benchmarkReturns.length);
        if (n < 2) {
            return Double.NaN;
        }
        double meanA = mean(assetReturns, n);
        double meanB = mean(benchmarkReturns, n);
        double cov = 0;
        double varB = 0;
        for (int i = 0; i < n; i++) {
            double da = assetReturns[i] - meanA;
            double db = benchmarkReturns[i] - meanB;
            cov += da * db;
            varB += db * db;
        }
        return varB == 0 ? Double.NaN : cov / varB;
    }

    /**
     * Annualised Jensen's alpha: return not explained by market exposure.
     * Uses a risk-free rate of 0 to keep it simple (Morocco's rate isn't 0).
     */
    public static double alpha(double[] assetReturns, double[] benchmarkReturns, double beta) {
        int n = Math.min(assetReturns.length, benchmarkReturns.length);
        if (n < 2 || Double.isNaN(beta)) {
            return Double.NaN;
        }
        return (mean(assetReturns, n) - beta * mean(benchmarkReturns, n)) * TRADING_DAYS;
    }

    // Liquidity

    /**
     * Amihud illiquidity: average of |daily return| / daily traded value.
     *
     * Higher means the price moves more per dirham traded (less liquid).
     * Sessions with 0 volume are skipped (ATW had 26 of 245), can't divide by 0.
     */
    public static double amihudIlliquidity(List<BigDecimal> closes, List<Long> volumes) {
        double sum = 0;
        int counted = 0;
        for (int i = 1; i < Math.min(closes.size(), volumes.size()); i++) {
            double prev = closes.get(i - 1).doubleValue();
            double curr = closes.get(i).doubleValue();
            long vol = volumes.get(i);
            if (prev <= 0 || vol <= 0) {
                continue;  // no trades that day, nothing to measure
            }
            double tradedValue = curr * vol;
            if (tradedValue > 0) {
                sum += Math.abs((curr - prev) / prev) / tradedValue;
                counted++;
            }
        }
        return counted == 0 ? 0.0 : sum / counted;
    }

    public static long zeroVolumeDays(List<Long> volumes) {
        return volumes.stream().filter(v -> v == null || v == 0L).count();
    }

    // Concentration

    /**
     * Herfindahl index of the weights: sum of w^2.
     * 1.0 = everything in one stock, 1/n = equal weights.
     * Better than "biggest holding %" because it looks at all holdings.
     */
    public static double herfindahl(Map<String, Double> weights) {
        return weights.values().stream().mapToDouble(w -> w * w).sum();
    }

    /** Total weight of the n biggest holdings. */
    public static double topNConcentration(Map<String, Double> weights, int n) {
        return weights.values().stream()
                .sorted(Comparator.reverseOrder())
                .limit(n)
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    // Performance

    /** Total simple return over the period. */
    public static double totalReturn(List<BigDecimal> closes) {
        if (closes.size() < 2) {
            return 0.0;
        }
        double first = closes.get(0).doubleValue();
        double last = closes.get(closes.size() - 1).doubleValue();
        return first == 0 ? 0.0 : (last - first) / first;
    }

    /** Return over the last days sessions. */
    public static double rollingReturn(List<BigDecimal> closes, int days) {
        if (closes.size() < days + 1) {
            return Double.NaN;
        }
        double start = closes.get(closes.size() - 1 - days).doubleValue();
        double end = closes.get(closes.size() - 1).doubleValue();
        return start == 0 ? 0.0 : (end - start) / start;
    }

    /** Sharpe ratio (risk-free = 0): annualised return / annualised volatility. */
    public static double sharpe(double[] logReturns) {
        double vol = realizedVolatility(logReturns);
        if (vol == 0) {
            return Double.NaN;
        }
        return (mean(logReturns, logReturns.length) * TRADING_DAYS) / vol;
    }

    /** Sortino: like Sharpe but divided by downside deviation. */
    public static double sortino(double[] logReturns) {
        double downside = downsideVolatility(logReturns);
        if (downside == 0) {
            return Double.NaN;
        }
        return (mean(logReturns, logReturns.length) * TRADING_DAYS) / downside;
    }

    // Helpers

    private static double mean(double[] values, int n) {
        if (n == 0) {
            return 0.0;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += values[i];
        }
        return sum / n;
    }

    /** Sample standard deviation (n-1). */
    public static double stdDev(double[] values) {
        if (values.length < 2) {
            return 0.0;
        }
        double m = mean(values, values.length);
        double sumSq = 0;
        for (double v : values) {
            sumSq += (v - m) * (v - m);
        }
        return Math.sqrt(sumSq / (values.length - 1));
    }

    /** Rounds to 6 decimals for the response. */
    public static BigDecimal round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return new BigDecimal(value, MathContext.DECIMAL64).setScale(6, RoundingMode.HALF_EVEN);
    }

    /**
     * Rounds to significant digits instead of decimals.
     * Amihud is around 1e-10 here, 6 decimals would show 0.000000.
     */
    public static BigDecimal roundSignificant(double value, int significantDigits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        if (value == 0.0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value, new MathContext(significantDigits, RoundingMode.HALF_EVEN));
    }
}
