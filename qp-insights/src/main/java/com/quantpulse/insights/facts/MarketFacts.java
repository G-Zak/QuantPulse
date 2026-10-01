package com.quantpulse.insights.facts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the brief can mention, already rounded the way it should be printed.
 * The model only sees this, and the validator only accepts numbers found here.
 *
 * We round here instead of asking the model to, because we don't want it doing math.
 */
public record MarketFacts(
        LocalDate asOf,
        String priceSource,
        boolean sampleData,
        List<IndexFact> indices,
        Breadth breadth,
        List<MoverFact> topGainers,
        List<MoverFact> topLosers,
        List<SectorFact> strongestSectors,
        List<SectorFact> weakestSectors,
        List<FxFact> fx,
        BenchmarkFact benchmark1Y,
        AlertsFact alerts24h,
        List<String> dataNotes) {

    public record IndexFact(String code, BigDecimal value, BigDecimal changePercent) {
    }

    public record Breadth(int advancers, int decliners, int unchanged) {
    }

    public record MoverFact(String ticker, String name, BigDecimal changePercent, BigDecimal price) {
    }

    public record SectorFact(String sector, BigDecimal averageChangePercent, int stocks) {
    }

    public record FxFact(String pair, BigDecimal rate, BigDecimal changePercent, LocalDate rateDate, String source) {
    }

    /** One-year returns, all in MAD. */
    public record BenchmarkFact(LocalDate windowEnd, String spyName, String eemName,
                                BigDecimal masiReturnPercent, BigDecimal spyReturnPercent,
                                BigDecimal eemReturnPercent, BigDecimal usdMadChangePercent) {
    }

    public record AlertsFact(int count, List<AlertExample> examples) {
    }

    public record AlertExample(String ticker, String reason, BigDecimal triggeredPrice) {
    }
}
