package com.quantpulse.portfolio.domain;

import com.quantpulse.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A holding in one instrument. Computed from the trade ledger and saved so we
 * don't replay every trade on each request. If they ever disagree, the ledger wins.
 */
@Entity
@Table(name = "position")
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "portfolio_id", nullable = false)
    private UUID portfolioId;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "cost_basis", nullable = false, precision = 19, scale = 6)
    private BigDecimal costBasis = BigDecimal.ZERO;

    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 6)
    private BigDecimal realizedPnl = BigDecimal.ZERO;

    @Column(name = "last_price", precision = 19, scale = 6)
    private BigDecimal lastPrice;

    @Column(name = "last_price_at")
    private Instant lastPriceAt;

    @Column(nullable = false, length = 3)
    private String currency = Money.MAD;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Optimistic lock.
     *
     * Two threads can handle prices for the same ticker at the same time. Without this the
     * second write overwrites the first. With it, one gets an exception, the message is
     * redelivered and retried. Collisions are rare, so this is cheaper than SELECT FOR UPDATE.
     */
    @Version
    private Long version;

    protected Position() {
    }

    public Position(UUID portfolioId, String ticker, String currency) {
        this.portfolioId = portfolioId;
        this.ticker = ticker;
        this.currency = currency;
    }

    public void apply(BigDecimal quantity, Money costBasis, Money realizedPnl) {
        this.quantity = quantity;
        this.costBasis = costBasis.amount();
        this.realizedPnl = realizedPnl.amount();
        this.currency = costBasis.currency();
        this.updatedAt = Instant.now();
    }

    /**
     * Applies a market price.
     * Ignores a price older than the one we have, since RabbitMQ can deliver out of order.
     *
     * @return true if the price was applied
     */
    public boolean applyMarketPrice(BigDecimal price, Instant asOf) {
        if (price == null) {
            return false;
        }
        if (lastPriceAt != null && asOf != null && asOf.isBefore(lastPriceAt)) {
            return false;
        }
        this.lastPrice = price;
        this.lastPriceAt = asOf;
        this.updatedAt = Instant.now();
        return true;
    }

    public Money costBasisMoney() {
        return Money.of(costBasis, currency);
    }

    public Money realizedPnlMoney() {
        return Money.of(realizedPnl, currency);
    }

    /** Current value, or null if no price yet. */
    public Money marketValue() {
        return lastPrice == null ? null : Money.of(lastPrice, currency).times(quantity);
    }

    public Money unrealizedPnl() {
        Money value = marketValue();
        return value == null ? null : value.minus(costBasisMoney());
    }

    public boolean isOpen() {
        return quantity.signum() > 0;
    }

    public Long getId() { return id; }
    public UUID getPortfolioId() { return portfolioId; }
    public String getTicker() { return ticker; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getCostBasis() { return costBasis; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public Instant getLastPriceAt() { return lastPriceAt; }
    public String getCurrency() { return currency; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
