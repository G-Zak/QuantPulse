package com.quantpulse.portfolio.service;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Gets an FX rate from qp-marketdata over HTTP (we can't read market.fx_rate, ADR-002).
 *
 * So converting depends on qp-marketdata being up. If it isn't, we throw
 * FxUnavailableException (503) instead of showing MAD amounts with a USD label.
 */
@Component
public class FxRateClient {

    private final RestClient marketData;

    public FxRateClient(RestClient marketDataClient) {
        this.marketData = marketDataClient;
    }

    record Quote(String base, String quote, BigDecimal rate, LocalDate rateDate, String source, boolean stale) {
    }

    public RiskService.FxApplied latest(String base, String quote) {
        List<Quote> quotes;
        try {
            quotes = marketData.get()
                    .uri("/api/v1/fx/rates?pairs={pair}", base + "-" + quote)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Quote>>() {});
        } catch (Exception e) {
            throw new FxUnavailableException(base, quote, "qp-marketdata unreachable: " + e.getMessage());
        }
        if (quotes == null || quotes.isEmpty() || quotes.get(0).rate() == null) {
            throw new FxUnavailableException(base, quote, "no stored rate");
        }
        Quote q = quotes.get(0);
        return new RiskService.FxApplied(q.base(), q.quote(), q.rate(), q.rateDate(), q.source(), q.stale());
    }

    public static class FxUnavailableException extends RuntimeException {
        public FxUnavailableException(String base, String quote, String reason) {
            super("no " + base + "/" + quote + " rate available (" + reason + ")");
        }
    }
}
