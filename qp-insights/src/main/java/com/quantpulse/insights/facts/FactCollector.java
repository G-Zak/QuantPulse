package com.quantpulse.insights.facts;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Builds MarketFacts from the other services' APIs.
 *
 * Each section is fetched on its own and can come back empty. The brief uses what
 * arrived and dataNotes lists what's missing, so a missing FX section only costs
 * one sentence.
 */
@Component
public class FactCollector {

    private static final Logger log = LoggerFactory.getLogger(FactCollector.class);
    private static final int MOVERS = 3;

    private final RestClient marketData;
    private final RestClient alerts;
    private final Clock clock;

    public FactCollector(RestClient marketDataClient, RestClient alertsClient, Clock clock) {
        this.marketData = marketDataClient;
        this.alerts = alertsClient;
        this.clock = clock;
    }

    public MarketFacts collect() {
        List<String> notes = new ArrayList<>();
        boolean sample = false;

        JsonNode overview = get(marketData, "/api/v1/overview").orElse(null);
        String priceSource = overview == null ? "UNKNOWN" : overview.path("priceSource").asText("UNKNOWN");
        if (!"DRAHMI".equals(priceSource)) {
            sample = true;
            notes.add("Stock prices and daily moves are " + priceSource.toLowerCase()
                    + " development data, not live market prices.");
        }

        List<MarketFacts.IndexFact> indices = new ArrayList<>();
        get(marketData, "/api/v1/indices").ifPresent(arr -> arr.forEach(i -> indices.add(
                new MarketFacts.IndexFact(i.path("code").asText(), dec(i, "value", 2), dec(i, "changePercent", 2)))));

        List<JsonNode> stocks = get(marketData, "/api/v1/instruments")
                .map(arr -> StreamSupport.stream(arr.spliterator(), false)
                        .filter(n -> n.hasNonNull("changePercent")).toList())
                .orElse(List.of());

        MarketFacts.Breadth breadth = null;
        List<MarketFacts.MoverFact> gainers = List.of();
        List<MarketFacts.MoverFact> losers = List.of();
        List<MarketFacts.SectorFact> strong = List.of();
        List<MarketFacts.SectorFact> weak = List.of();
        if (stocks.isEmpty()) {
            notes.add("No per-stock daily changes were available.");
        } else {
            int up = 0, down = 0, flat = 0;
            for (JsonNode s : stocks) {
                int sign = new BigDecimal(s.path("changePercent").asText()).signum();
                if (sign > 0) up++;
                else if (sign < 0) down++;
                else flat++;
            }
            breadth = new MarketFacts.Breadth(up, down, flat);
            Comparator<JsonNode> byChange = Comparator.comparing(n -> new BigDecimal(n.path("changePercent").asText()));
            gainers = stocks.stream().sorted(byChange.reversed()).limit(MOVERS)
                    .filter(n -> n.path("changePercent").asDouble() > 0).map(FactCollector::mover).toList();
            losers = stocks.stream().sorted(byChange).limit(MOVERS)
                    .filter(n -> n.path("changePercent").asDouble() < 0).map(FactCollector::mover).toList();
            List<MarketFacts.SectorFact> sectors = sectors(stocks);
            strong = sectors.stream().limit(2).toList();
            weak = sectors.reversed().stream().limit(2).toList();
        }

        List<MarketFacts.FxFact> fx = new ArrayList<>();
        get(marketData, "/api/v1/fx/rates?pairs=USD-MAD,EUR-MAD").ifPresent(arr -> arr.forEach(q -> {
            fx.add(new MarketFacts.FxFact(q.path("base").asText() + "/" + q.path("quote").asText(),
                    dec(q, "rate", 4), dec(q, "changePercent", 2),
                    LocalDate.parse(q.path("rateDate").asText()), q.path("source").asText()));
        }));
        if (fx.isEmpty()) {
            notes.add("No FX rates were available.");
        } else if (fx.stream().anyMatch(f -> "SYNTHETIC".equals(f.source()))) {
            sample = true;
            notes.add("FX rates are synthetic sample data until recorded from Alpha Vantage.");
        }

        Optional<JsonNode> comparison = get(marketData, "/api/v1/benchmarks/compare?range=1Y")
                .filter(b -> b.path("series").size() > 0);
        MarketFacts.BenchmarkFact benchmark = comparison.map(b -> {
            Map<String, BigDecimal> ret = new LinkedHashMap<>();
            b.path("series").forEach(s -> ret.put(s.path("code").asText(), dec(s, "returnMadPercent", 1)));
            return new MarketFacts.BenchmarkFact(LocalDate.parse(b.path("end").asText()),
                    "S&P 500 ETF (SPY)", "MSCI Emerging Markets ETF (EEM)",
                    ret.get("MASI"), ret.get("SPY"), ret.get("EEM"), dec(b, "usdMadChangePercent", 1));
        }).orElse(null);
        if (comparison.map(b -> b.path("sampleData").asBoolean()).orElse(false)) {
            sample = true;
            notes.add("The one-year comparison with SPY and EEM uses synthetic sample data for the US funds.");
        }

        MarketFacts.AlertsFact alertsFact = alerts24h();

        LocalDate asOf = LocalDate.now(clock);
        if (indices.isEmpty() && stocks.isEmpty()) {
            log.warn("[FACTS] qp-marketdata returned no indices and no stocks");
        }
        return new MarketFacts(asOf, priceSource, sample, indices, breadth, gainers, losers, strong, weak,
                fx, benchmark, alertsFact, notes);
    }

    private MarketFacts.AlertsFact alerts24h() {
        Instant cutoff = Instant.now(clock).minus(Duration.ofHours(24));
        return get(alerts, "/api/v1/alerts/firings?limit=50").map(arr -> {
            List<JsonNode> recent = StreamSupport.stream(arr.spliterator(), false)
                    .filter(f -> f.hasNonNull("firedAt") && Instant.parse(f.path("firedAt").asText()).isAfter(cutoff))
                    .toList();
            List<MarketFacts.AlertExample> examples = recent.stream().limit(3)
                    .map(f -> new MarketFacts.AlertExample(f.path("ticker").asText(), f.path("reason").asText(),
                            dec(f, "triggeredPrice", 2)))
                    .toList();
            return new MarketFacts.AlertsFact(recent.size(), examples);
        }).orElse(null);
    }

    private static List<MarketFacts.SectorFact> sectors(List<JsonNode> stocks) {
        Map<String, List<BigDecimal>> bySector = new LinkedHashMap<>();
        for (JsonNode s : stocks) {
            if (s.hasNonNull("sector")) {
                bySector.computeIfAbsent(s.path("sector").asText(), k -> new ArrayList<>())
                        .add(new BigDecimal(s.path("changePercent").asText()));
            }
        }
        return bySector.entrySet().stream()
                .filter(e -> e.getValue().size() >= 2)  // a sector with one stock is just a mover
                .map(e -> new MarketFacts.SectorFact(e.getKey(),
                        e.getValue().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                                .divide(BigDecimal.valueOf(e.getValue().size()), 2, RoundingMode.HALF_EVEN),
                        e.getValue().size()))
                .sorted(Comparator.comparing(MarketFacts.SectorFact::averageChangePercent).reversed())
                .toList();
    }

    private static MarketFacts.MoverFact mover(JsonNode n) {
        return new MarketFacts.MoverFact(n.path("ticker").asText(), n.path("name").asText(),
                dec(n, "changePercent", 2), dec(n, "price", 2));
    }

    private static BigDecimal dec(JsonNode n, String field, int scale) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : new BigDecimal(v.asText()).setScale(scale, RoundingMode.HALF_EVEN);
    }

    private Optional<JsonNode> get(RestClient client, String path) {
        try {
            return Optional.ofNullable(client.get().uri(path).retrieve().body(JsonNode.class));
        } catch (Exception e) {
            log.warn("[FACTS] {} unavailable: {}", path, e.getMessage());
            return Optional.empty();
        }
    }
}
