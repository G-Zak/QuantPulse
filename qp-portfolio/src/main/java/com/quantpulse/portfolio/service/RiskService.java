package com.quantpulse.portfolio.service;

import com.quantpulse.common.money.Money;
import com.quantpulse.portfolio.domain.Position;
import com.quantpulse.portfolio.repository.PositionRepository;
import com.quantpulse.portfolio.risk.RiskCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Risk metrics from OHLCV history, fetched from qp-marketdata's API.
 * We can't read the market schema (ADR-002). Costs an HTTP call, but qp-marketdata
 * can change its tables without breaking us.
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final RestClient marketData;
    private final PositionRepository positions;

    public RiskService(RestClient marketDataClient, PositionRepository positions) {
        this.marketData = marketDataClient;
        this.positions = positions;
    }

    public record Bar(LocalDate date, BigDecimal open, BigDecimal high,
                      BigDecimal low, BigDecimal close, long volume) {
    }

    public record InstrumentRisk(
            String ticker, String range, int sessions,
            BigDecimal realizedVolAnnualized, BigDecimal downsideVolAnnualized,
            BigDecimal maxDrawdown, BigDecimal var95, BigDecimal cvar95,
            BigDecimal beta, BigDecimal alpha, BigDecimal sharpe, BigDecimal sortino,
            BigDecimal totalReturn, BigDecimal amihudIlliquidity, long zeroVolumeDays) {
    }

    /** Indices use a different endpoint than instruments. */
    private static final java.util.Set<String> INDEX_CODES =
            java.util.Set.of("MASI", "MASI20", "MADEX");

    public List<Bar> history(String symbol, String range) {
        String path = INDEX_CODES.contains(symbol.toUpperCase())
                ? "/api/v1/indices/{s}/history?range={range}"
                : "/api/v1/instruments/{s}/history?range={range}";
        try {
            List<Bar> bars = marketData.get()
                    .uri(path, symbol, range)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Bar>>() {});
            return bars == null ? List.of() : bars;
        } catch (Exception e) {
            log.warn("[RISK] could not fetch history for {}: {}", symbol, e.getMessage());
            return List.of();
        }
    }

    /**
     * Risk profile of one instrument, optionally against an index.
     *
     * The two series are matched by date before computing beta. Matching by position would
     * compare different days when one has a missing session, and the result would still
     * look fine.
     */
    public InstrumentRisk computeInstrumentRisk(String ticker, String range, String benchmark) {
        List<Bar> bars = history(ticker, range);
        if (bars.size() < 3) {
            return new InstrumentRisk(ticker, range, bars.size(),
                    null, null, null, null, null, null, null, null, null, null, null, 0);
        }

        List<BigDecimal> closes = bars.stream().map(Bar::close).toList();
        List<Long> volumes = bars.stream().map(Bar::volume).toList();
        double[] logRet = RiskCalculator.logReturns(closes);

        BigDecimal beta = null;
        BigDecimal alpha = null;
        if (benchmark != null && !benchmark.isBlank()) {
            Aligned aligned = alignWithBenchmark(bars, benchmark, range);
            if (aligned.assetReturns().length >= 2) {
                double b = RiskCalculator.beta(aligned.assetReturns(), aligned.benchmarkReturns());
                beta = RiskCalculator.round(b);
                alpha = RiskCalculator.round(
                        RiskCalculator.alpha(aligned.assetReturns(), aligned.benchmarkReturns(), b));
            }
        }

        return new InstrumentRisk(
                ticker, range, bars.size(),
                RiskCalculator.round(RiskCalculator.realizedVolatility(logRet)),
                RiskCalculator.round(RiskCalculator.downsideVolatility(logRet)),
                RiskCalculator.round(RiskCalculator.maxDrawdown(closes)),
                RiskCalculator.round(RiskCalculator.valueAtRisk(logRet, 0.95)),
                RiskCalculator.round(RiskCalculator.conditionalVaR(logRet, 0.95)),
                beta, alpha,
                RiskCalculator.round(RiskCalculator.sharpe(logRet)),
                RiskCalculator.round(RiskCalculator.sortino(logRet)),
                RiskCalculator.round(RiskCalculator.totalReturn(closes)),
                RiskCalculator.roundSignificant(RiskCalculator.amihudIlliquidity(closes, volumes), 4),
                RiskCalculator.zeroVolumeDays(volumes));
    }

    private record Aligned(double[] assetReturns, double[] benchmarkReturns) {
    }

    /** Keeps only dates both series have, then computes returns. */
    private Aligned alignWithBenchmark(List<Bar> bars, String benchmark, String range) {
        List<Bar> benchBars = history(benchmark, range);
        if (benchBars.isEmpty()) {
            return new Aligned(new double[0], new double[0]);
        }
        Map<LocalDate, BigDecimal> benchByDate = new HashMap<>();
        benchBars.forEach(b -> benchByDate.put(b.date(), b.close()));

        List<BigDecimal> a = new ArrayList<>();
        List<BigDecimal> b = new ArrayList<>();
        for (Bar bar : bars) {
            BigDecimal benchClose = benchByDate.get(bar.date());
            if (benchClose != null) {
                a.add(bar.close());
                b.add(benchClose);
            }
        }
        return new Aligned(RiskCalculator.logReturns(a), RiskCalculator.logReturns(b));
    }

    public record PortfolioRisk(
            UUID portfolioId, Money totalValue, Money totalCost,
            Money unrealizedPnl, Money realizedPnl, BigDecimal unrealizedPnlPercent,
            Map<String, BigDecimal> weights, Map<String, BigDecimal> sectorAllocation,
            BigDecimal herfindahl, BigDecimal top5Concentration, int positionCount,
            FxApplied fx) {

        /**
         * The same summary in another currency, at one rate.
         *
         * Only the four amounts are converted. Percentages, weights and concentration are
         * MAD / MAD, so they don't change. This isn't a foreign investor's P&amp;L, that would
         * use the rate of each purchase date.
         */
        public PortfolioRisk inCurrency(FxApplied applied) {
            String target = applied.quote();
            return new PortfolioRisk(portfolioId,
                    totalValue.convert(applied.rate(), target), totalCost.convert(applied.rate(), target),
                    unrealizedPnl.convert(applied.rate(), target), realizedPnl.convert(applied.rate(), target),
                    unrealizedPnlPercent, weights, sectorAllocation, herfindahl, top5Concentration,
                    positionCount, applied);
        }
    }

    /** The rate used for the conversion, returned with it. */
    public record FxApplied(String base, String quote, BigDecimal rate, LocalDate rateDate,
                            String source, boolean stale) {
    }

    /**
     * Portfolio summary.
     * Concentration uses the Herfindahl index (sum of squared weights), which looks at
     * all holdings, not just the biggest one.
     */
    @Transactional(readOnly = true)
    public PortfolioRisk computePortfolioRisk(UUID portfolioId) {
        List<Position> open = positions.findOpenPositions(portfolioId);

        Money totalValue = Money.zeroMad();
        Money totalCost = Money.zeroMad();
        Money realized = Money.zeroMad();
        Map<String, Money> valueByTicker = new LinkedHashMap<>();

        for (Position p : open) {
            Money value = p.marketValue();
            // No price yet: count it at cost instead of leaving it out.
            Money effective = value != null ? value : p.costBasisMoney();
            valueByTicker.put(p.getTicker(), effective);
            totalValue = totalValue.plus(effective);
            totalCost = totalCost.plus(p.costBasisMoney());
            realized = realized.plus(p.realizedPnlMoney());
        }

        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        Map<String, Double> weightsForStats = new HashMap<>();
        if (totalValue.isPositive()) {
            for (Map.Entry<String, Money> e : valueByTicker.entrySet()) {
                BigDecimal w = e.getValue().amount()
                        .divide(totalValue.amount(), 6, java.math.RoundingMode.HALF_EVEN);
                weights.put(e.getKey(), w);
                weightsForStats.put(e.getKey(), w.doubleValue());
            }
        }

        Money unrealized = totalValue.minus(totalCost);
        BigDecimal unrealizedPct = totalCost.isZero() ? BigDecimal.ZERO
                : unrealized.amount().divide(totalCost.amount(), 6, java.math.RoundingMode.HALF_EVEN)
                    .multiply(BigDecimal.valueOf(100));

        return new PortfolioRisk(portfolioId, totalValue, totalCost, unrealized, realized,
                unrealizedPct, weights, Map.of(),
                RiskCalculator.round(RiskCalculator.herfindahl(weightsForStats)),
                RiskCalculator.round(RiskCalculator.topNConcentration(weightsForStats, 5)),
                open.size(), null);
    }
}
