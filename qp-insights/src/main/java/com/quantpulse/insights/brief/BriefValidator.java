package com.quantpulse.insights.brief;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.quantpulse.insights.llm.BriefDraft;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a brief from the model before we publish it.
 *
 * Every number in the text must be in the facts we gave the model (rounding allowed,
 * sign ignored since "fell 0.35%" is the fact -0.35). Any other number was either
 * made up or calculated by the model, so the brief is refused and the template is used.
 *
 * It can't catch everything: "rose 0.35%" passes when the fact is -0.35.
 * But a made-up number can't get through.
 *
 * Also checked: no advice or forecasts, sample data must be mentioned, and length.
 */
@Component
public class BriefValidator {

    /** A number not stuck to letters, so MASI20 or Q3 don't count. */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\p{L}\\d.,])[-+−]?(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)(?![\\d\\p{L}])");
    private static final Pattern DIGITS = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern ADVICE = Pattern.compile(
            "\\b(you should|we recommend|recommend(s|ed)?|should (buy|sell|hold)|consider (buying|selling)"
                    + "|buy now|sell now|target price|price target|is expected to|will (rise|fall|rebound|climb|drop|recover|continue))\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DISCLOSURE = Pattern.compile("\\b(sample|synthetic|simulated)\\b", Pattern.CASE_INSENSITIVE);

    public record Verdict(boolean accepted, List<String> problems, List<String> factsUsed) {
    }

    public Verdict validate(BriefDraft draft, JsonNode facts) {
        List<String> problems = new ArrayList<>();
        String text = draft.headline() + "\n" + draft.body();

        if (draft.headline().isBlank() || draft.headline().length() > 120) {
            problems.add("headline length " + draft.headline().length() + " outside 1-120");
        }
        if (draft.body().length() < 80 || draft.body().length() > 1500) {
            problems.add("body length " + draft.body().length() + " outside 80-1500");
        }

        Set<BigDecimal> allowed = allowedNumbers(facts);
        Set<String> unsupported = new java.util.LinkedHashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            String token = m.group(1).replace(",", "");
            if (!supported(new BigDecimal(token), allowed)) {
                unsupported.add(m.group().trim());
            }
        }
        if (!unsupported.isEmpty()) {
            problems.add("numbers not in the facts: " + String.join(", ", unsupported));
        }

        Matcher advice = ADVICE.matcher(text);
        if (advice.find()) {
            problems.add("advice or forecast language: \"" + advice.group() + "\"");
        }

        if (facts.path("sampleData").asBoolean(false) && !DISCLOSURE.matcher(draft.body()).find()) {
            problems.add("facts include sample data but the body does not say so");
        }

        return new Verdict(problems.isEmpty(), problems, resolvable(draft.factsUsed(), facts));
    }

    static boolean supported(BigDecimal token, Set<BigDecimal> allowed) {
        BigDecimal t = token.abs();
        int decimals = Math.max(0, t.scale());
        for (BigDecimal f : allowed) {
            if (f.setScale(decimals, RoundingMode.HALF_UP).compareTo(t) == 0
                    || f.setScale(decimals, RoundingMode.HALF_EVEN).compareTo(t) == 0) {
                return true;
            }
        }
        return false;
    }

    /** All numbers in the facts, including digits inside strings and field names (dates, alerts24h). */
    static Set<BigDecimal> allowedNumbers(JsonNode node) {
        Set<BigDecimal> out = new HashSet<>();
        collect(node, out);
        return out;
    }

    private static void collect(JsonNode node, Set<BigDecimal> out) {
        if (node.isNumber()) {
            out.add(node.decimalValue().abs());
        } else if (node.isTextual()) {
            Matcher d = DIGITS.matcher(node.asText());
            while (d.find()) {
                out.add(new BigDecimal(d.group()));
            }
        } else if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                Matcher d = DIGITS.matcher(name);
                while (d.find()) {
                    out.add(new BigDecimal(d.group()));
                }
                collect(node.get(name), out);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, out));
        }
    }

    /** Keeps only paths like fx[0].rate that exist in the facts. */
    static List<String> resolvable(List<String> paths, JsonNode facts) {
        List<String> ok = new ArrayList<>();
        for (String p : paths == null ? List.<String>of() : paths) {
            String pointer = "/" + p.trim().replaceAll("\\[(\\d+)]", ".$1").replace('.', '/');
            try {
                if (!facts.at(JsonPointer.compile(pointer)).isMissingNode()) {
                    ok.add(p.trim());
                }
            } catch (IllegalArgumentException ignored) {
                // not a path, skip it
            }
        }
        return ok;
    }
}
