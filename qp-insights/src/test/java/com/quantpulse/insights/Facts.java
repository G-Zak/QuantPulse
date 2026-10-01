package com.quantpulse.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.quantpulse.insights.facts.MarketFacts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Sample facts for tests, and a mapper set up like Spring Boot's. */
public final class Facts {

    public static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private Facts() {
    }

    public static MarketFacts sample(boolean sampleData) {
        return new MarketFacts(
                LocalDate.of(2026, 9, 28), sampleData ? "SIMULATED" : "DRAHMI", sampleData,
                List.of(new MarketFacts.IndexFact("MASI", new BigDecimal("17839.02"), new BigDecimal("-0.35")),
                        new MarketFacts.IndexFact("MASI20", new BigDecimal("1317.20"), new BigDecimal("-0.41"))),
                new MarketFacts.Breadth(31, 44, 6),
                List.of(new MarketFacts.MoverFact("MNG", "MANAGEM", new BigDecimal("3.21"), new BigDecimal("1326.15"))),
                List.of(new MarketFacts.MoverFact("IAM", "ITISSALAT AL-MAGHRIB", new BigDecimal("-2.10"), new BigDecimal("93.35"))),
                List.of(new MarketFacts.SectorFact("MINES", new BigDecimal("1.40"), 5)),
                List.of(new MarketFacts.SectorFact("TELEC", new BigDecimal("-1.10"), 2)),
                List.of(new MarketFacts.FxFact("USD/MAD", new BigDecimal("9.1845"), new BigDecimal("0.27"),
                                LocalDate.of(2026, 9, 25), sampleData ? "SYNTHETIC" : "ALPHAVANTAGE"),
                        new MarketFacts.FxFact("EUR/MAD", new BigDecimal("10.7312"), new BigDecimal("0.58"),
                                LocalDate.of(2026, 9, 25), sampleData ? "SYNTHETIC" : "ALPHAVANTAGE")),
                new MarketFacts.BenchmarkFact(LocalDate.of(2026, 7, 31), "S&P 500 ETF (SPY)",
                        "MSCI Emerging Markets ETF (EEM)", new BigDecimal("-8.7"), new BigDecimal("4.2"),
                        new BigDecimal("0.7"), new BigDecimal("-0.5")),
                new MarketFacts.AlertsFact(2, List.of(new MarketFacts.AlertExample("ATW", "PRICE_ABOVE", new BigDecimal("690.00")))),
                sampleData ? List.of("FX rates are synthetic sample data until recorded from Alpha Vantage.") : List.of());
    }

    public static JsonNode tree(MarketFacts f) {
        return MAPPER.valueToTree(f);
    }
}
