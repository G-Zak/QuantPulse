package com.quantpulse.portfolio.domain;

import com.quantpulse.common.money.Money;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An open purchase lot, only used for FIFO.
 * FIFO needs to know which shares are sold, so each purchase is its own row.
 */
@Entity
@Table(name = "tax_lot")
public class TaxLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "portfolio_id", nullable = false)
    private UUID portfolioId;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "original_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal originalQuantity;

    @Column(name = "remaining_quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal remainingQuantity;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal unitCost;

    @Column(nullable = false, length = 3)
    private String currency = Money.MAD;

    protected TaxLot() {
    }

    public TaxLot(UUID portfolioId, String ticker, Instant acquiredAt,
                  BigDecimal quantity, Money unitCost) {
        this.portfolioId = portfolioId;
        this.ticker = ticker;
        this.acquiredAt = acquiredAt;
        this.originalQuantity = quantity;
        this.remainingQuantity = quantity;
        this.unitCost = unitCost.amount();
        this.currency = unitCost.currency();
    }

    public void consume(BigDecimal qty) {
        this.remainingQuantity = this.remainingQuantity.subtract(qty);
    }

    public Money unitCostMoney() {
        return Money.of(unitCost, currency);
    }

    public boolean isOpen() {
        return remainingQuantity.signum() > 0;
    }

    public Long getId() { return id; }
    public String getTicker() { return ticker; }
    public Instant getAcquiredAt() { return acquiredAt; }
    public BigDecimal getRemainingQuantity() { return remainingQuantity; }
    public BigDecimal getOriginalQuantity() { return originalQuantity; }
    public BigDecimal getUnitCost() { return unitCost; }
    public String getCurrency() { return currency; }
}
