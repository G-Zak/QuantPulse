package com.quantpulse.portfolio.domain;

import com.quantpulse.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the trade ledger. Never updated or deleted: a correction is a new row.
 * That way any past position can be rebuilt by replaying the ledger.
 */
@Entity
@Table(name = "trade_transaction")
public class TradeTransaction {

    public enum Type { BUY, SELL, DIVIDEND, SPLIT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "portfolio_id", nullable = false)
    private UUID portfolioId;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    /** NUMERIC, not an integer: splits can give fractional shares. */
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal price;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal fees = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = Money.MAD;

    /** When the trade happened (given by the caller, can be in the past). */
    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    /** When we recorded it. */
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt = Instant.now();

    @Column
    private String note;

    protected TradeTransaction() {
    }

    public TradeTransaction(UUID portfolioId, String ticker, Type type, BigDecimal quantity,
                            Money price, Money fees, Instant executedAt, String note) {
        this.portfolioId = portfolioId;
        this.ticker = ticker;
        this.type = type;
        this.quantity = quantity;
        this.price = price.amount();
        this.fees = fees.amount();
        this.currency = price.currency();
        this.executedAt = executedAt;
        this.note = note;
    }

    public Money priceMoney() {
        return Money.of(price, currency);
    }

    public Money feesMoney() {
        return Money.of(fees, currency);
    }

    /** Cash effect including fees. Negative for a buy. */
    public Money netCashFlow() {
        Money gross = priceMoney().times(quantity);
        return switch (type) {
            case BUY -> gross.plus(feesMoney()).negated();
            case SELL -> gross.minus(feesMoney());
            case DIVIDEND -> gross.minus(feesMoney());
            case SPLIT -> Money.zero(currency);
        };
    }

    public Long getId() { return id; }
    public UUID getPortfolioId() { return portfolioId; }
    public String getTicker() { return ticker; }
    public Type getType() { return type; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getFees() { return fees; }
    public String getCurrency() { return currency; }
    public Instant getExecutedAt() { return executedAt; }
    public Instant getRecordedAt() { return recordedAt; }
    public String getNote() { return note; }
}
