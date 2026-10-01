package com.quantpulse.marketdata.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.quantpulse.marketdata.quota.AlphaVantageQuotaGovernor;
import com.quantpulse.marketdata.quota.AlphaVantageQuotaGovernor.Priority;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Real Alpha Vantage calls. Only active in the live profile.
 *
 * Same layers as DrahmiHttpProvider: quota governor, then circuit breaker, then retry.
 * It has its own circuit breaker (alphavantage), so one API being down doesn't affect
 * the other.
 *
 * Alpha Vantage returns 200 with a short message for a used-up key, a bad key or an
 * unknown symbol. Every response goes through detectSoftFailure before we use it.
 * A rate-limit message marks today's budget as used. These aren't retried or counted
 * by the circuit breaker, since the API itself is fine.
 *
 * The key is a default URI variable on the RestClient. We log the function name, never the URL.
 */
@Component
@Profile("live")
public class AlphaVantageHttpClient implements AlphaVantageClient {

    private static final Logger log = LoggerFactory.getLogger(AlphaVantageHttpClient.class);
    private static final String CB = "alphavantage";

    private final RestClient client;
    private final AlphaVantageQuotaGovernor quota;

    public AlphaVantageHttpClient(RestClient alphaVantageRestClient, AlphaVantageQuotaGovernor quota) {
        this.client = alphaVantageRestClient;
        this.quota = quota;
    }

    @Override
    public String sourceName() {
        return "ALPHAVANTAGE";
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "fxFallback")
    @Retry(name = CB)
    public Optional<AlphaVantageDtos.FxRateDto> fetchFxRate(String fromCurrency, String toCurrency) {
        JsonNode body = query("fx-" + fromCurrency + "-" + toCurrency, Priority.CRITICAL,
                "/query?function={function}&from_currency={from}&to_currency={to}&apikey={apikey}",
                Map.of("function", "CURRENCY_EXCHANGE_RATE", "from", fromCurrency, "to", toCurrency));
        return AlphaVantageDtos.parseFxRate(body);
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "seriesFallback")
    @Retry(name = CB)
    public List<AlphaVantageDtos.OhlcvPointDto> fetchDailySeries(String symbol, boolean full) {
        JsonNode body = query("daily-" + symbol, Priority.NORMAL,
                "/query?function={function}&symbol={symbol}&outputsize={size}&apikey={apikey}",
                Map.of("function", "TIME_SERIES_DAILY", "symbol", symbol, "size", full ? "full" : "compact"));
        return AlphaVantageDtos.parseDailySeries(body);
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "fxDailyFallback")
    @Retry(name = CB)
    public List<AlphaVantageDtos.FxDailyPointDto> fetchFxDaily(String fromCurrency, String toCurrency, boolean full) {
        JsonNode body = query("fxdaily-" + fromCurrency + "-" + toCurrency, Priority.NORMAL,
                "/query?function={function}&from_symbol={from}&to_symbol={to}&outputsize={size}&apikey={apikey}",
                Map.of("function", "FX_DAILY", "from", fromCurrency, "to", toCurrency,
                        "size", full ? "full" : "compact"));
        return AlphaVantageDtos.parseFxDaily(body);
    }

    // Request

    private JsonNode query(String jobName, Priority priority, String template, Map<String, String> vars) {
        if (!quota.tryAcquire(jobName, priority)) {
            throw new QuotaExhaustedException(jobName, priority);
        }

        String function = vars.get("function");
        long started = System.nanoTime();
        int[] status = {0};
        try {
            JsonNode body = client.get()
                    // {apikey} comes from the RestClient's default URI variables.
                    .uri(template, vars)
                    .exchange((request, response) -> {
                        status[0] = response.getStatusCode().value();
                        if (response.getStatusCode().is5xxServerError()) {
                            throw new UpstreamServerException(status[0], function);
                        }
                        if (response.getStatusCode().isError()) {
                            throw new UpstreamRejectedException(function, "HTTP " + status[0]);
                        }
                        return response.bodyTo(JsonNode.class);
                    });

            Optional<AlphaVantageDtos.SoftFailure> failure = AlphaVantageDtos.detectSoftFailure(body);
            if (failure.isPresent()) {
                AlphaVantageDtos.SoftFailure f = failure.get();
                if (f.kind() == AlphaVantageDtos.SoftFailure.Kind.RATE_LIMITED) {
                    quota.recordUpstreamRefusal(jobName);
                    throw new UpstreamRefusedException(function, f.message());
                }
                throw new UpstreamRejectedException(function, f.message());
            }
            return body;
        } finally {
            long ms = (System.nanoTime() - started) / 1_000_000;
            quota.recordOutcome(jobName, function, status[0] == 0 ? null : status[0], ms);
        }
    }

    // Fallbacks. Return empty instead of throwing, like Drahmi: yesterday's rate is still
    // useful. FxConversionService decides if it's too old.

    @SuppressWarnings("unused")
    private Optional<AlphaVantageDtos.FxRateDto> fxFallback(String from, String to, Throwable t) {
        logFallback("fetchFxRate(" + from + "/" + to + ")", t);
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    private List<AlphaVantageDtos.OhlcvPointDto> seriesFallback(String symbol, boolean full, Throwable t) {
        logFallback("fetchDailySeries(" + symbol + (full ? ", full" : "") + ")", t);
        return List.of();
    }

    @SuppressWarnings("unused")
    private List<AlphaVantageDtos.FxDailyPointDto> fxDailyFallback(String from, String to, boolean full, Throwable t) {
        logFallback("fetchFxDaily(" + from + "/" + to + ")", t);
        return List.of();
    }

    private void logFallback(String op, Throwable t) {
        if (t instanceof QuotaExhaustedException) {
            log.info("[ALPHAVANTAGE] {} skipped — {}", op, t.getMessage());
        } else {
            log.warn("[ALPHAVANTAGE] {} fell back — {}: {}", op, t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /** Our own budget said no. Not an API problem. */
    public static class QuotaExhaustedException extends RuntimeException {
        public QuotaExhaustedException(String job, Priority priority) {
            super("alphavantage quota floor reached for job=" + job + " priority=" + priority);
        }
    }

    /** A 200 saying we're over the limit. The key is done for today. */
    public static class UpstreamRefusedException extends RuntimeException {
        public UpstreamRefusedException(String function, String message) {
            super(function + " refused by upstream: " + message);
        }
    }

    /** The request is wrong (bad symbol, function or key). Retrying won't help. */
    public static class UpstreamRejectedException extends RuntimeException {
        public UpstreamRejectedException(String function, String message) {
            super(function + " rejected by upstream: " + message);
        }
    }

    public static class UpstreamServerException extends RuntimeException {
        public UpstreamServerException(int status, String function) {
            super("alphavantage " + status + " for " + function);
        }
    }
}
