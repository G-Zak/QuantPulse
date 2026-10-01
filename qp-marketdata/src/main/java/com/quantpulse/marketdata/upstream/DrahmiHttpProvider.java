package com.quantpulse.marketdata.upstream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.quantpulse.marketdata.quota.QuotaGovernor;
import com.quantpulse.marketdata.quota.QuotaGovernor.JobPriority;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * Real Drahmi calls. Only active in the live profile, the others use
 * FixtureMarketDataProvider and spend nothing.
 *
 * Each call goes through, from outside to inside:
 * 1. Quota governor: says no before anything is sent. It must be outside the retry,
 *    or a retry would spend budget again.
 * 2. Circuit breaker: stops calling an API that is down.
 * 3. Retry: only for network errors.
 *
 * Resilience4j wraps it as Retry(CircuitBreaker(call)), so the breaker sees each
 * attempt. 4xx are not retried: a 404 or 429 won't succeed and costs budget.
 */
@Component
@Profile("live")
public class DrahmiHttpProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(DrahmiHttpProvider.class);
    private static final String CB = "drahmi";

    private final RestClient client;
    private final QuotaGovernor quota;

    public DrahmiHttpProvider(RestClient drahmiRestClient, QuotaGovernor quota) {
        this.client = drahmiRestClient;
        this.quota = quota;
    }

    @Override
    public String sourceName() {
        return "LIVE";
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "snapshotFallback")
    @Retry(name = CB)
    public List<DrahmiDtos.StockSummary> fetchMarketSnapshot() {
        // The whole market in one call.
        return get("market-snapshot", JobPriority.NORMAL, "/stocks?limit=200",
                new ParameterizedTypeReference<DrahmiDtos.StockPage>() {})
                .map(DrahmiDtos.StockPage::items)
                .orElseGet(List::of);
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "indicesFallback")
    @Retry(name = CB)
    public List<DrahmiDtos.IndexDto> fetchIndices() {
        return get("indices", JobPriority.HIGH, "/indices",
                new ParameterizedTypeReference<List<DrahmiDtos.IndexDto>>() {})
                .orElseGet(List::of);
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "moversFallback")
    @Retry(name = CB)
    public List<DrahmiDtos.StockSummary> fetchMovers() {
        List<DrahmiDtos.StockSummary> merged = new java.util.ArrayList<>();
        for (String path : List.of("/market/gainers?limit=50", "/market/losers?limit=50",
                                   "/market/most-active?limit=50")) {
            get("movers", JobPriority.NORMAL, path,
                    new ParameterizedTypeReference<DrahmiDtos.StockPage>() {})
                    .map(DrahmiDtos.StockPage::items)
                    .ifPresent(merged::addAll);
        }
        return merged;
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "sectorsFallback")
    @Retry(name = CB)
    public List<DrahmiDtos.SectorDto> fetchSectors() {
        return get("sectors", JobPriority.NORMAL, "/sectors",
                new ParameterizedTypeReference<List<DrahmiDtos.SectorDto>>() {})
                .orElseGet(List::of);
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "detailFallback")
    @Retry(name = CB)
    public Optional<DrahmiDtos.StockDetail> fetchStockDetail(String ticker) {
        return get("stock-detail", JobPriority.NORMAL, "/stocks/" + ticker,
                new ParameterizedTypeReference<DrahmiDtos.StockDetail>() {});
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "historyFallback")
    @Retry(name = CB)
    public List<DrahmiDtos.OhlcvPoint> fetchHistory(String ticker, String range) {
        return get("history-backfill", JobPriority.BACKFILL,
                "/stocks/" + ticker + "/history?range=" + range,
                new ParameterizedTypeReference<DrahmiDtos.HistoryResponse>() {})
                .map(DrahmiDtos.HistoryResponse::points)
                .orElseGet(List::of);
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "indexHistoryFallback")
    @Retry(name = CB)
    public List<DrahmiDtos.IndexHistoryPoint> fetchIndexHistory(String code, String range) {
        return get("index-history", JobPriority.BACKFILL,
                "/indices/" + code + "/history?range=" + range,
                new ParameterizedTypeReference<List<DrahmiDtos.IndexHistoryPoint>>() {})
                .orElseGet(List::of);
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "dividendsFallback")
    @Retry(name = CB)
    public List<DrahmiDtos.DividendDto> fetchDividends(String ticker) {
        // BACKFILL priority: dividends rarely change, so this never takes the last calls
        // from the price snapshot.
        return get("dividends", JobPriority.BACKFILL, "/stocks/" + ticker + "/dividends",
                new ParameterizedTypeReference<List<DrahmiDtos.DividendDto>>() {})
                .orElseGet(List::of);
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "overviewFallback")
    @Retry(name = CB)
    public Optional<DrahmiDtos.MarketOverview> fetchOverview() {
        return get("overview", JobPriority.NORMAL, "/market/overview",
                new ParameterizedTypeReference<DrahmiDtos.MarketOverview>() {});
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "statusFallback")
    @Retry(name = CB)
    public Optional<DrahmiDtos.MarketStatus> fetchStatus() {
        return get("status", JobPriority.CRITICAL, "/market/status",
                new ParameterizedTypeReference<DrahmiDtos.MarketStatus>() {});
    }

    @Override
    @CircuitBreaker(name = CB, fallbackMethod = "riskFallback")
    @Retry(name = CB)
    public Optional<DrahmiDtos.RiskMetrics> fetchRisk(String ticker, String range, String benchmark) {
        return get("risk", JobPriority.BACKFILL,
                "/intelligence/stocks/" + ticker + "/risk?range=" + range + "&benchmark=" + benchmark,
                new ParameterizedTypeReference<DrahmiDtos.Envelope<DrahmiDtos.RiskMetrics>>() {})
                .map(DrahmiDtos.Envelope::data);
    }

    // Request

    private <T> Optional<T> get(String jobName, JobPriority priority, String path,
                                ParameterizedTypeReference<T> type) {
        if (!quota.tryAcquire(jobName, priority, 1)) {
            throw new QuotaExhaustedException(jobName, priority);
        }

        long started = System.nanoTime();
        int[] status = {0};
        Integer[] remaining = {null};

        try {
            T body = client.get()
                    .uri(path)
                    .exchange((request, response) -> {
                        status[0] = response.getStatusCode().value();
                        remaining[0] = parseRemaining(response.getHeaders().getFirst("X-RateLimit-Remaining"));

                        HttpStatusCode code = response.getStatusCode();
                        if (code.value() == 429) {
                            throw new UpstreamRateLimitedException(
                                    response.getHeaders().getFirst("Retry-After"));
                        }
                        if (code.is4xxClientError()) {
                            // Not retried and not counted by the breaker: the request is wrong
                            // (unknown ticker, bad param). Return empty.
                            log.warn("[DRAHMI] {} -> {} for {}", code.value(), code, path);
                            return null;
                        }
                        if (code.isError()) {
                            throw new UpstreamServerException(code.value(), path);
                        }
                        return response.bodyTo(type);
                    });
            return Optional.ofNullable(body);
        } finally {
            long ms = (System.nanoTime() - started) / 1_000_000;
            quota.recordOutcome(jobName, path, status[0] == 0 ? null : status[0], ms, remaining[0]);
        }
    }

    private static Integer parseRemaining(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Integer.valueOf(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Fallbacks: circuit open, retries used up, or no quota left.
    // Return empty instead of throwing, old data is better than a crash.

    @SuppressWarnings("unused")
    private List<DrahmiDtos.StockSummary> snapshotFallback(Throwable t) {
        logFallback("fetchMarketSnapshot", t);
        return List.of();
    }

    @SuppressWarnings("unused")
    private List<DrahmiDtos.IndexDto> indicesFallback(Throwable t) {
        logFallback("fetchIndices", t);
        return List.of();
    }

    @SuppressWarnings("unused")
    private List<DrahmiDtos.StockSummary> moversFallback(Throwable t) {
        logFallback("fetchMovers", t);
        return List.of();
    }

    @SuppressWarnings("unused")
    private List<DrahmiDtos.SectorDto> sectorsFallback(Throwable t) {
        logFallback("fetchSectors", t);
        return List.of();
    }

    @SuppressWarnings("unused")
    private Optional<DrahmiDtos.StockDetail> detailFallback(String ticker, Throwable t) {
        logFallback("fetchStockDetail(" + ticker + ")", t);
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    private List<DrahmiDtos.OhlcvPoint> historyFallback(String ticker, String range, Throwable t) {
        logFallback("fetchHistory(" + ticker + ")", t);
        return List.of();
    }

    @SuppressWarnings("unused")
    private List<DrahmiDtos.IndexHistoryPoint> indexHistoryFallback(String code, String range, Throwable t) {
        logFallback("fetchIndexHistory(" + code + ")", t);
        return List.of();
    }

    @SuppressWarnings("unused")
    private List<DrahmiDtos.DividendDto> dividendsFallback(String ticker, Throwable t) {
        logFallback("fetchDividends(" + ticker + ")", t);
        return List.of();
    }

    @SuppressWarnings("unused")
    private Optional<DrahmiDtos.MarketOverview> overviewFallback(Throwable t) {
        logFallback("fetchOverview", t);
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    private Optional<DrahmiDtos.MarketStatus> statusFallback(Throwable t) {
        logFallback("fetchStatus", t);
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    private Optional<DrahmiDtos.RiskMetrics> riskFallback(String ticker, String range,
                                                          String benchmark, Throwable t) {
        logFallback("fetchRisk(" + ticker + ")", t);
        return Optional.empty();
    }

    private void logFallback(String op, Throwable t) {
        if (t instanceof QuotaExhaustedException) {
            log.info("[DRAHMI] {} skipped — {}", op, t.getMessage());
        } else {
            log.warn("[DRAHMI] {} fell back — {}: {}", op, t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /** Our budget said no. Expected, not an error. */
    public static class QuotaExhaustedException extends RuntimeException {
        public QuotaExhaustedException(String job, JobPriority priority) {
            super("quota floor reached for job=" + job + " priority=" + priority);
        }
    }

    /** The API returned 429. Not the same as our own budget saying no. */
    public static class UpstreamRateLimitedException extends RuntimeException {
        private final String retryAfter;

        public UpstreamRateLimitedException(String retryAfter) {
            super("upstream returned 429, Retry-After=" + retryAfter);
            this.retryAfter = retryAfter;
        }

        public String getRetryAfter() {
            return retryAfter;
        }
    }

    public static class UpstreamServerException extends RuntimeException {
        public UpstreamServerException(int status, String path) {
            super("upstream " + status + " for " + path);
        }
    }
}
