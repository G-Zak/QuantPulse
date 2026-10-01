package com.quantpulse.marketdata.upstream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Drahmi API types. They match the JSON exactly; UpstreamMapper turns them into
 * domain objects, so if the API changes only this side changes.
 *
 * Numbers are boxed types: the docs say beta: 0.85 but the API sends null, and a
 * primitive double would turn that into 0.0.
 *
 * ignoreUnknown = true because the API sends fields that aren't in the docs
 * (id, changeSource, description), and those shouldn't break ingestion.
 */
public final class DrahmiDtos {

    private DrahmiDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StockSummary(
            String ticker,
            String name,
            BigDecimal price,
            BigDecimal change,
            String sector,
            String currency,
            BigDecimal marketCap,
            Instant updatedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StockPage(List<StockSummary> items, String cursor, Boolean hasMore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StockDetail(
            String ticker,
            String name,
            String isin,
            String sector,
            String exchange,
            String currency,
            BigDecimal price,
            BigDecimal change,
            BigDecimal marketCap,
            BigDecimal dividendYield,
            BigDecimal peRatio,
            /** The docs say number, the API sends null. Keep it boxed. */
            BigDecimal beta,
            BigDecimal volume24h,
            BigDecimal week52High,
            BigDecimal week52Low,
            Instant updatedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SectorDto(String code, String name, Integer count) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DividendDto(
            String id,
            LocalDate exDate,
            LocalDate paymentDate,
            BigDecimal amount,
            String currency
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexDto(
            String code,
            String name,
            BigDecimal value,
            BigDecimal changePercent,
            BigDecimal changeValue,
            Instant updatedAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndexHistoryPoint(
            LocalDate date,
            BigDecimal value,
            BigDecimal changePercent,
            BigDecimal changeValue
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OhlcvPoint(
            LocalDate date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            /** Has decimals in the feed (it's an average), so not a long. */
            BigDecimal volume
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryResponse(String ticker, List<OhlcvPoint> points) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketOverview(
            BigDecimal totalMarketCap,
            Integer activeStocks,
            BigDecimal marketChange,
            Instant updatedAt
    ) {
    }

    /**
     * Says isOpen: true after the Casablanca close, with timezone UTC.
     * We save it but don't trust it, TradingCalendar decides.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MarketStatus(Boolean isOpen, String status, String timezone, Instant updatedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskMetrics(
            String ticker,
            String range,
            BigDecimal realizedVolAnnualized,
            BigDecimal downsideVolAnnualized,
            BigDecimal maxDrawdown,
            BigDecimal var95,
            BigDecimal cvar95,
            BigDecimal beta,
            BigDecimal alpha
    ) {
    }

    /** Wrapper returned when includeMetadata=true. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope<T>(T data, Metadata metadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(
            LocalDate asOfDate,
            String dataDelay,
            Integer dataQualityScore,
            Boolean normalized,
            String methodologyVersion
    ) {
    }
}
