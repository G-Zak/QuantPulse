package com.quantpulse.marketdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A stock listed on the Casablanca exchange.
 *
 * The primary key is a generated id, not the ticker, because tickers can change
 * (renames, re-listings). The ticker is just unique.
 */
@Entity
@Table(name = "instrument")
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16)
    private String ticker;

    @Column(nullable = false)
    private String name;

    @Column(length = 12)
    private String isin;

    @Column(name = "sector_code", length = 32)
    private String sectorCode;

    @Column(nullable = false, length = 64)
    private String exchange = "Bourse de Casablanca";

    @Column(nullable = false, length = 3)
    private String currency = "MAD";

    @Column(name = "last_price", precision = 19, scale = 6)
    private BigDecimal lastPrice;

    @Column(name = "last_price_at")
    private Instant lastPriceAt;

    @Column(name = "market_cap", precision = 24, scale = 2)
    private BigDecimal marketCap;

    @Column(name = "dividend_yield", precision = 9, scale = 4)
    private BigDecimal dividendYield;

    @Column(name = "pe_ratio", precision = 12, scale = 4)
    private BigDecimal peRatio;

    @Column(name = "week52_high", precision = 19, scale = 6)
    private BigDecimal week52High;

    @Column(name = "week52_low", precision = 19, scale = 6)
    private BigDecimal week52Low;

    @Column(name = "day_change_percent", precision = 9, scale = 4)
    private BigDecimal dayChangePercent;

    /** Always null from the API, qp-portfolio computes its own. */
    @Column(precision = 12, scale = 6)
    private BigDecimal beta;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Optimistic lock: if two ingestion runs update the same row, one gets an exception
     * instead of overwriting the other.
     */
    @Version
    private Long version;

    protected Instrument() {
    }

    public Instrument(String ticker, String name) {
        this.ticker = ticker;
        this.name = name;
    }

    /**
     * Sets a new price.
     *
     * @return true if the price changed. Only then do we send an event.
     */
    public boolean applyPrice(BigDecimal newPrice, BigDecimal dayChangePercent, Instant asOf) {
        if (newPrice == null) {
            return false;
        }
        this.dayChangePercent = dayChangePercent;
        boolean changed = lastPrice == null || lastPrice.compareTo(newPrice) != 0;
        this.lastPrice = newPrice;
        this.lastPriceAt = asOf;
        this.updatedAt = Instant.now();
        return changed;
    }

    public void applyFundamentals(BigDecimal marketCap, BigDecimal dividendYield,
                                  BigDecimal peRatio, BigDecimal week52High,
                                  BigDecimal week52Low, String isin) {
        if (marketCap != null) this.marketCap = marketCap;
        if (dividendYield != null) this.dividendYield = dividendYield;
        if (peRatio != null) this.peRatio = peRatio;
        if (week52High != null) this.week52High = week52High;
        if (week52Low != null) this.week52Low = week52Low;
        if (isin != null) this.isin = isin;
        this.updatedAt = Instant.now();
    }

    /** Overwrites, unlike applyFundamentals: our own range from stored bars is better. */
    public void applyWeek52Range(BigDecimal high, BigDecimal low) {
        this.week52High = high;
        this.week52Low = low;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTicker() { return ticker; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIsin() { return isin; }
    public String getSectorCode() { return sectorCode; }
    public void setSectorCode(String sectorCode) { this.sectorCode = sectorCode; }
    public String getExchange() { return exchange; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public Instant getLastPriceAt() { return lastPriceAt; }
    public BigDecimal getMarketCap() { return marketCap; }
    public BigDecimal getDividendYield() { return dividendYield; }
    public BigDecimal getPeRatio() { return peRatio; }
    public BigDecimal getWeek52High() { return week52High; }
    public BigDecimal getWeek52Low() { return week52Low; }
    public BigDecimal getDayChangePercent() { return dayChangePercent; }
    public BigDecimal getBeta() { return beta; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
