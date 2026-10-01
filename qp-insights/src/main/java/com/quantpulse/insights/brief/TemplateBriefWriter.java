package com.quantpulse.insights.brief;

import com.quantpulse.insights.facts.MarketFacts;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The brief without AI: fixed sentences filled with the facts.
 *
 * Used when there's no key, no budget left, the API is down, or the draft was refused.
 * It only repeats the facts, so it passes the same validator (there's a test for that).
 */
@Component
public class TemplateBriefWriter {

    public record Written(String headline, String body, List<String> factsUsed) {
    }

    public Written write(MarketFacts f) {
        List<String> sentences = new ArrayList<>();
        List<String> used = new ArrayList<>();
        String headline = "Casablanca market brief";

        for (int i = 0; i < f.indices().size(); i++) {
            MarketFacts.IndexFact idx = f.indices().get(i);
            if (idx.value() == null || idx.changePercent() == null) {
                continue;
            }
            String move = move(idx.changePercent());
            if (i == 0) {
                headline = idx.code() + " " + (idx.changePercent().signum() >= 0 ? "up " : "down ")
                        + idx.changePercent().abs().toPlainString() + "%";
                sentences.add("The " + idx.code() + " " + move + " to " + idx.value().toPlainString() + ".");
            } else {
                sentences.add("The " + idx.code() + " " + move + ", closing at " + idx.value().toPlainString() + ".");
            }
            used.add("indices[" + i + "].changePercent");
        }

        if (f.breadth() != null) {
            sentences.add(f.breadth().advancers() + " stocks advanced, " + f.breadth().decliners()
                    + " declined and " + f.breadth().unchanged() + " were unchanged.");
            used.add("breadth");
        }

        if (!f.topGainers().isEmpty() && !f.topLosers().isEmpty()) {
            MarketFacts.MoverFact g = f.topGainers().get(0);
            MarketFacts.MoverFact l = f.topLosers().get(0);
            sentences.add(g.name() + " led the gainers, up " + g.changePercent().abs().toPlainString()
                    + "%, while " + l.name() + " fell the most, down " + l.changePercent().abs().toPlainString() + "%.");
            used.add("topGainers[0].changePercent");
            used.add("topLosers[0].changePercent");
        }

        MarketFacts.FxFact usd = fx(f, "USD/MAD");
        MarketFacts.FxFact eur = fx(f, "EUR/MAD");
        if (usd != null && eur != null) {
            sentences.add("One dollar bought " + usd.rate().toPlainString() + " dirhams and one euro "
                    + eur.rate().toPlainString() + " (rates of " + usd.rateDate() + ").");
            headline += " · USD/MAD " + usd.rate().toPlainString();
            used.add("fx[0].rate");
            used.add("fx[1].rate");
        }

        MarketFacts.BenchmarkFact b = f.benchmark1Y();
        if (b != null && b.masiReturnPercent() != null && b.spyReturnPercent() != null) {
            sentences.add("Over the year to " + b.windowEnd() + ", in dirhams, the MASI " + ret(b.masiReturnPercent())
                    + " while the " + b.spyName() + " " + ret(b.spyReturnPercent())
                    + (b.eemReturnPercent() == null ? "" : " and the " + b.eemName() + " " + ret(b.eemReturnPercent()))
                    + ".");
            used.add("benchmark1Y");
        }

        if (f.alerts24h() != null && f.alerts24h().count() > 0) {
            sentences.add(f.alerts24h().count() + (f.alerts24h().count() == 1 ? " price alert" : " price alerts")
                    + " fired in the last 24 hours.");
            used.add("alerts24h.count");
        }

        if (!f.dataNotes().isEmpty()) {
            sentences.add("Note: this brief uses sample data. " + String.join(" ", f.dataNotes()));
        }
        return new Written(headline, String.join(" ", sentences), used);
    }

    private static MarketFacts.FxFact fx(MarketFacts f, String pair) {
        return f.fx().stream().filter(x -> pair.equals(x.pair()) && x.rate() != null).findFirst().orElse(null);
    }

    private static String move(BigDecimal pct) {
        return switch (pct.signum()) {
            case 1 -> "rose " + pct.toPlainString() + "%";
            case -1 -> "fell " + pct.abs().toPlainString() + "%";
            default -> "was unchanged";
        };
    }

    private static String ret(BigDecimal pct) {
        return pct.signum() >= 0 ? "gained " + pct.toPlainString() + "%" : "lost " + pct.abs().toPlainString() + "%";
    }
}
