package com.quantpulse.marketdata.upstream;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns Alpha Vantage JSON into our own types.
 *
 * Their JSON uses labels as keys ("5. Exchange Rate", "4. close"), sends numbers as
 * strings, and reports errors inside a 200. Callers only get typed records with
 * BigDecimal values, or a SoftFailure.
 *
 * Parsed by hand so all the odd key names are in one place.
 */
public final class AlphaVantageDtos {

    private static final DateTimeFormatter REFRESHED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AlphaVantageDtos() {
    }

    /** One spot rate: units of toCurrency for 1 fromCurrency. */
    public record FxRateDto(String fromCurrency, String toCurrency, BigDecimal rate,
                            BigDecimal bid, BigDecimal ask, ZonedDateTime lastRefreshed) {

        /** Market date of the rate, in the zone the API used. */
        public LocalDate rateDate() {
            return lastRefreshed.toLocalDate();
        }
    }

    public record OhlcvPointDto(LocalDate date, BigDecimal open, BigDecimal high,
                                BigDecimal low, BigDecimal close, long volume) {
    }

    /** One daily FX close: units of toCurrency for 1 fromCurrency. */
    public record FxDailyPointDto(String fromCurrency, String toCurrency, LocalDate date, BigDecimal close) {
    }

    /**
     * Why a 200 response had no data.
     *
     * - RATE_LIMITED: a "Note" or "Information" message. Usually means over the limit.
     *   We can't always tell from the text, so we treat it as a refusal to be safe.
     * - REJECTED: an "Error Message" (bad symbol, bad function, bad key). Retrying won't help.
     * - An "Information" that mentions premium is REJECTED too: the free tier doesn't serve
     *   that request (e.g. outputsize=full). It doesn't mean the key is used up.
     */
    public record SoftFailure(Kind kind, String message) {
        public enum Kind { RATE_LIMITED, REJECTED }
    }

    /** Finds an error hidden in a 200. Call it before parsing. */
    public static Optional<SoftFailure> detectSoftFailure(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return Optional.of(new SoftFailure(SoftFailure.Kind.REJECTED, "empty body"));
        }
        if (body.hasNonNull("Error Message")) {
            return Optional.of(new SoftFailure(SoftFailure.Kind.REJECTED, body.get("Error Message").asText()));
        }
        for (String key : List.of("Note", "Information")) {
            if (body.hasNonNull(key)) {
                String message = body.get(key).asText();
                if (message.toLowerCase(java.util.Locale.ROOT).contains("premium")) {
                    return Optional.of(new SoftFailure(SoftFailure.Kind.REJECTED, message));
                }
                return Optional.of(new SoftFailure(SoftFailure.Kind.RATE_LIMITED, body.get(key).asText()));
            }
        }
        return Optional.empty();
    }

    /** Parses CURRENCY_EXCHANGE_RATE. Call detectSoftFailure first. */
    public static Optional<FxRateDto> parseFxRate(JsonNode body) {
        JsonNode r = body.path("Realtime Currency Exchange Rate");
        if (r.isMissingNode() || !r.hasNonNull("5. Exchange Rate")) {
            return Optional.empty();
        }
        ZoneId zone = ZoneId.of(r.path("7. Time Zone").asText("UTC"));
        LocalDateTime refreshed = LocalDateTime.parse(r.path("6. Last Refreshed").asText(), REFRESHED);
        return Optional.of(new FxRateDto(
                r.path("1. From_Currency Code").asText(),
                r.path("3. To_Currency Code").asText(),
                decimal(r, "5. Exchange Rate"),
                decimal(r, "8. Bid Price"),
                decimal(r, "9. Ask Price"),
                refreshed.atZone(zone)));
    }

    /** Parses TIME_SERIES_DAILY into bars, oldest first. */
    public static List<OhlcvPointDto> parseDailySeries(JsonNode body) {
        JsonNode series = body.path("Time Series (Daily)");
        List<OhlcvPointDto> points = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = series.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode bar = e.getValue();
            points.add(new OhlcvPointDto(
                    LocalDate.parse(e.getKey()),
                    decimal(bar, "1. open"),
                    decimal(bar, "2. high"),
                    decimal(bar, "3. low"),
                    decimal(bar, "4. close"),
                    bar.path("5. volume").asLong()));
        }
        // The API sends newest first, we use oldest first everywhere.
        points.sort(Comparator.comparing(OhlcvPointDto::date));
        return points;
    }

    /** Parses FX_DAILY into closes, oldest first. */
    public static List<FxDailyPointDto> parseFxDaily(JsonNode body) {
        JsonNode meta = body.path("Meta Data");
        String from = meta.path("2. From Symbol").asText();
        String to = meta.path("3. To Symbol").asText();
        List<FxDailyPointDto> points = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = body.path("Time Series FX (Daily)").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            BigDecimal close = decimal(e.getValue(), "4. close");
            if (close != null && close.signum() > 0) {
                points.add(new FxDailyPointDto(from, to, LocalDate.parse(e.getKey()), close));
            }
        }
        points.sort(Comparator.comparing(FxDailyPointDto::date));
        return points;
    }

    /** String to BigDecimal directly, never through double. */
    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : new BigDecimal(v.asText());
    }
}
