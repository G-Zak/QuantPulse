package com.quantpulse.marketdata.upstream;

import java.util.List;
import java.util.Optional;

/**
 * What qp-marketdata needs from a market data source.
 *
 * DrahmiHttpProvider (live profile) calls the real API with its 100/day limit.
 * FixtureMarketDataProvider (dev, test) replays the 36 saved responses for free.
 *
 * fetchMarketSnapshot() returns all instruments at once because the API gives all 81
 * in one call. A fetchQuote(ticker) method would lead to 81 calls.
 */
public interface MarketDataProvider {

    /** All instruments with current prices. One call. */
    List<DrahmiDtos.StockSummary> fetchMarketSnapshot();

    /** All indices (MASI, MASI20). One call. */
    List<DrahmiDtos.IndexDto> fetchIndices();

    List<DrahmiDtos.SectorDto> fetchSectors();

    /**
     * Top gainers, losers and most active, merged.
     *
     * These lists include marketCap, which /stocks doesn't. 3 calls a day instead of 81.
     * Only instruments that appear in a list get a market cap.
     */
    List<DrahmiDtos.StockSummary> fetchMovers();

    Optional<DrahmiDtos.StockDetail> fetchStockDetail(String ticker);

    /** OHLCV history of one instrument. One call per instrument, the expensive one. */
    List<DrahmiDtos.OhlcvPoint> fetchHistory(String ticker, String range);

    List<DrahmiDtos.IndexHistoryPoint> fetchIndexHistory(String code, String range);

    /** Dividend history of one instrument. One call per instrument. */
    List<DrahmiDtos.DividendDto> fetchDividends(String ticker);

    Optional<DrahmiDtos.MarketOverview> fetchOverview();

    Optional<DrahmiDtos.MarketStatus> fetchStatus();

    /** The API's own risk numbers, used to check ours. */
    Optional<DrahmiDtos.RiskMetrics> fetchRisk(String ticker, String range, String benchmark);

    /** Which implementation is running, shown on events and in the UI. */
    String sourceName();
}
