package com.quantpulse.api.bff;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Backend for the frontend.
 *
 * The browser only talks to this service. The others stay internal, so auth and CORS
 * are handled in one place. It also combines several backend calls into one response
 * so the browser doesn't make three round trips.
 */
@RestController
@RequestMapping("/api/v1")
public class GatewayController {

    private final RestClient marketData;
    private final RestClient portfolio;
    private final RestClient alerts;
    private final RestClient quant;
    private final RestClient insights;

    public GatewayController(RestClient marketDataClient, RestClient portfolioClient,
                             RestClient alertsClient, RestClient quantClient, RestClient insightsClient) {
        this.marketData = marketDataClient;
        this.portfolio = portfolioClient;
        this.alerts = alertsClient;
        this.quant = quantClient;
        this.insights = insightsClient;
    }

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    // Market (public)

    /**
     * Everything the market page needs in one request.
     *
     * The three calls run in parallel. If one fails its section comes back empty
     * instead of breaking the whole page.
     */
    @GetMapping("/market/dashboard")
    public Map<String, Object> dashboard() {
        var instruments = CompletableFuture.supplyAsync(
                () -> safeList(marketData, "/api/v1/instruments"));
        var indices = CompletableFuture.supplyAsync(
                () -> safeList(marketData, "/api/v1/indices"));
        var overview = CompletableFuture.supplyAsync(
                () -> safeMap(marketData, "/api/v1/overview"));

        CompletableFuture.allOf(instruments, indices, overview).join();

        Map<String, Object> body = new HashMap<>();
        body.put("instruments", instruments.join());
        body.put("indices", indices.join());
        body.put("overview", overview.join());
        return body;
    }

    @GetMapping("/market/instruments")
    public List<Map<String, Object>> instruments(@RequestParam(required = false) String sector) {
        String path = sector == null ? "/api/v1/instruments" : "/api/v1/instruments?sector=" + sector;
        return safeList(marketData, path);
    }

    @GetMapping("/market/instruments/{ticker}")
    public ResponseEntity<Map<String, Object>> instrument(@PathVariable String ticker) {
        Map<String, Object> body = safeMap(marketData, "/api/v1/instruments/" + ticker);
        return body.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(body);
    }

    @GetMapping("/market/instruments/{ticker}/history")
    public List<Map<String, Object>> history(@PathVariable String ticker,
                                             @RequestParam(defaultValue = "1Y") String range) {
        return safeList(marketData, "/api/v1/instruments/" + ticker + "/history?range=" + range);
    }

    /** Instrument detail and its risk profile, fetched in parallel. */
    @GetMapping("/market/instruments/{ticker}/detail")
    public Map<String, Object> instrumentDetail(@PathVariable String ticker,
                                                @RequestParam(defaultValue = "6M") String range) {
        var detail = CompletableFuture.supplyAsync(
                () -> safeMap(marketData, "/api/v1/instruments/" + ticker));
        var bars = CompletableFuture.supplyAsync(
                () -> safeList(marketData, "/api/v1/instruments/" + ticker + "/history?range=" + range));
        var risk = CompletableFuture.supplyAsync(
                () -> safeMap(portfolio, "/api/v1/portfolios/instruments/" + ticker
                        + "/risk?range=" + range + "&benchmark=MASI"));

        CompletableFuture.allOf(detail, bars, risk).join();

        Map<String, Object> body = new HashMap<>();
        body.put("instrument", detail.join());
        body.put("history", bars.join());
        body.put("risk", risk.join());
        return body;
    }

    @GetMapping("/market/indices")
    public List<Map<String, Object>> indices() {
        return safeList(marketData, "/api/v1/indices");
    }

    @GetMapping("/market/sectors")
    public List<Map<String, Object>> sectors() {
        return safeList(marketData, "/api/v1/sectors");
    }

    @GetMapping("/market/search")
    public List<Map<String, Object>> search(@RequestParam String q) {
        return safeList(marketData, "/api/v1/search?q=" + q);
    }

    @GetMapping("/market/quota")
    public Map<String, Object> quota() {
        return safeMap(marketData, "/api/v1/ops/quota");
    }

    /** Latest dirham rates with the daily change, e.g. pairs=USD-MAD,EUR-MAD */
    @GetMapping("/market/fx/rates")
    public List<Map<String, Object>> fxRates(@RequestParam(defaultValue = "USD-MAD,EUR-MAD") String pairs) {
        return safeList(marketData, "/api/v1/fx/rates?pairs=" + pairs);
    }

    @GetMapping("/market/benchmarks/compare")
    public Map<String, Object> compareBenchmarks(@RequestParam(defaultValue = "1Y") String range) {
        return safeMap(marketData, "/api/v1/benchmarks/compare?range=" + range);
    }

    // AI brief. Reading is public. Regenerating needs a login because it uses the daily AI budget.

    @GetMapping("/market/brief")
    public ResponseEntity<Map<String, Object>> brief() {
        Map<String, Object> body = safeMap(insights, "/api/v1/brief/latest");
        return body.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(body);
    }

    @PostMapping("/market/brief/regenerate")
    public ResponseEntity<Map<String, Object>> regenerateBrief() {
        return passThrough(insights, "/api/v1/brief/regenerate", Map.of());
    }

    // Portfolio (logged in)

    @GetMapping("/portfolios")
    public List<Map<String, Object>> portfolios(Authentication auth) {
        // Only the caller's portfolios, otherwise any user could list everyone's.
        return safeList(portfolio, "/api/v1/portfolios?owner=" + auth.getName());
    }

    @PostMapping("/portfolios")
    public Map<String, Object> createPortfolio(Authentication auth,
                                               @RequestBody Map<String, Object> body) {
        Map<String, Object> payload = new HashMap<>(body);
        // The owner comes from the token, never from the request body.
        payload.put("owner", auth.getName());
        return post(portfolio, "/api/v1/portfolios", payload);
    }

    @GetMapping("/portfolios/{id}/positions")
    public List<Map<String, Object>> positions(@PathVariable String id) {
        return safeList(portfolio, "/api/v1/portfolios/" + id + "/positions");
    }

    /**
     * Passes the status through: a 503 (no FX rate) must reach the browser as a 503,
     * not as an empty summary.
     */
    @GetMapping("/portfolios/{id}/summary")
    public ResponseEntity<Map<String, Object>> portfolioSummary(@PathVariable String id,
                                                                @RequestParam(defaultValue = "MAD") String currency) {
        try {
            Map<String, Object> body = portfolio.get()
                    .uri("/api/v1/portfolios/{id}/summary?currency={ccy}", id, currency)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(MAP);
            return ResponseEntity.ok(body == null ? Map.of() : body);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getResponseBodyAsString()));
        }
    }

    @GetMapping("/portfolios/{id}/transactions")
    public List<Map<String, Object>> transactions(@PathVariable String id) {
        return safeList(portfolio, "/api/v1/portfolios/" + id + "/transactions");
    }

    @PostMapping("/portfolios/{id}/buy")
    public ResponseEntity<Map<String, Object>> buy(@PathVariable String id,
                                                   @RequestBody Map<String, Object> body) {
        return passThrough(portfolio, "/api/v1/portfolios/" + id + "/buy", body);
    }

    @PostMapping("/portfolios/{id}/sell")
    public ResponseEntity<Map<String, Object>> sell(@PathVariable String id,
                                                    @RequestBody Map<String, Object> body) {
        return passThrough(portfolio, "/api/v1/portfolios/" + id + "/sell", body);
    }

    // Alerts (logged in)

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(Authentication auth) {
        return safeList(alerts, "/api/v1/alerts?owner=" + auth.getName());
    }

    @PostMapping("/alerts")
    public Map<String, Object> createAlert(Authentication auth, @RequestBody Map<String, Object> body) {
        Map<String, Object> payload = new HashMap<>(body);
        payload.put("owner", auth.getName());
        return post(alerts, "/api/v1/alerts", payload);
    }

    @DeleteMapping("/alerts/{id}")
    public ResponseEntity<Void> deleteAlert(@PathVariable String id) {
        alerts.delete().uri("/api/v1/alerts/" + id).retrieve().toBodilessEntity();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/alerts/firings")
    public List<Map<String, Object>> firings(@RequestParam(defaultValue = "50") int limit) {
        return safeList(alerts, "/api/v1/alerts/firings?limit=" + limit);
    }

    // Analytics (public). Proxied so the browser still talks to one origin and qp-quant stays internal.

    @GetMapping("/analysis/market-report")
    public Map<String, Object> marketReport(@RequestParam(defaultValue = "1Y") String range,
                                            @RequestParam(defaultValue = "40") int limit) {
        return safeMap(quant, "/analysis/market-report?range=" + range + "&limit=" + limit);
    }

    @GetMapping("/analysis/forecast/{ticker}")
    public Map<String, Object> forecast(@PathVariable String ticker,
                                        @RequestParam(defaultValue = "60") int horizon,
                                        @RequestParam(defaultValue = "1Y") String range) {
        return safeMap(quant, "/analysis/forecast/" + ticker
                + "?horizon=" + horizon + "&range=" + range + "&sims=15000");
    }

    @GetMapping("/analysis/anomalies/{ticker}")
    public Map<String, Object> anomalies(@PathVariable String ticker,
                                         @RequestParam(defaultValue = "1Y") String range) {
        return safeMap(quant, "/analysis/anomalies/" + ticker + "?range=" + range);
    }

    @GetMapping("/analysis/income/screen")
    public Map<String, Object> incomeScreen(@RequestParam(defaultValue = "30") int limit) {
        return safeMap(quant, "/analysis/income/screen?limit=" + limit);
    }

    @GetMapping("/analysis/fx-rates")
    public Map<String, Object> fxRates() {
        return safeMap(quant, "/analysis/fx-rates");
    }

    @PostMapping("/analysis/income")
    public ResponseEntity<Map<String, Object>> income(@RequestBody Map<String, Object> body) {
        return passThrough(quant, "/analysis/income", body);
    }

    @PostMapping("/analysis/optimise")
    public Map<String, Object> optimise(@RequestBody Map<String, Object> body) {
        return post(quant, "/analysis/optimise", body);
    }

    // Helpers

    private List<Map<String, Object>> safeList(RestClient client, String path) {
        try {
            List<Map<String, Object>> body = client.get().uri(path)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(LIST_OF_MAPS);
            return body == null ? List.of() : body;
        } catch (Exception e) {
            // Only this section fails, not the whole page.
            return List.of();
        }
    }

    private Map<String, Object> safeMap(RestClient client, String path) {
        try {
            Map<String, Object> body = client.get().uri(path)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(MAP);
            return body == null ? Map.of() : body;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> post(RestClient client, String path, Object body) {
        Map<String, Object> result = client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(MAP);
        return result == null ? Map.of() : result;
    }

    /**
     * Forwards a write and keeps the status from the service.
     * For example selling more shares than you own returns 400 from qp-portfolio.
     */
    private ResponseEntity<Map<String, Object>> passThrough(RestClient client, String path, Object body) {
        try {
            return ResponseEntity.ok(post(client, path, body));
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        }
    }
}
