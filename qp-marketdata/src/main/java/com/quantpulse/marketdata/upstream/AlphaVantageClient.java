package com.quantpulse.marketdata.upstream;

import java.util.List;
import java.util.Optional;

/**
 * What we use Alpha Vantage for: FX rates and daily prices of SPY and EEM.
 * It doesn't cover the Casablanca exchange, so it's kept apart from MarketDataProvider.
 * The HTTP client runs under the live profile, the fixture client everywhere else.
 */
public interface AlphaVantageClient {

    /** Spot rate for a pair. One call. */
    Optional<AlphaVantageDtos.FxRateDto> fetchFxRate(String fromCurrency, String toCurrency);

    /** Last ~100 daily bars. One call. */
    default List<AlphaVantageDtos.OhlcvPointDto> fetchDailySeries(String symbol) {
        return fetchDailySeries(symbol, false);
    }

    /**
     * Daily bars. full=true asks for the whole history, which the free tier may refuse
     * (we then get an empty list).
     */
    List<AlphaVantageDtos.OhlcvPointDto> fetchDailySeries(String symbol, boolean full);

    /** Daily FX closes for a pair. One call. */
    List<AlphaVantageDtos.FxDailyPointDto> fetchFxDaily(String fromCurrency, String toCurrency, boolean full);

    /** Where a dataset came from. Fixture data can be SYNTHETIC, and the UI labels it as sample data. */
    default String sourceFor(String dataset) {
        return sourceName();
    }

    /** Saved with every rate as its source. */
    String sourceName();
}
