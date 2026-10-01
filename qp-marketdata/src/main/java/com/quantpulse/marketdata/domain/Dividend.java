package com.quantpulse.marketdata.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A declared dividend, per share.
 * The key is (ticker, exDate), not the vendor's id, so re-running the backfill doesn't duplicate.
 */
@Entity
@Table(name = "dividend")
public class Dividend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String ticker;

    /** Buy before this date to get the dividend. */
    @Column(name = "ex_date", nullable = false)
    private LocalDate exDate;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    /** Per share. */
    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "MAD";

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt = Instant.now();

    protected Dividend() {
    }

    public Dividend(String ticker, LocalDate exDate, LocalDate paymentDate,
                    BigDecimal amount, String currency) {
        this.ticker = ticker;
        this.exDate = exDate;
        this.paymentDate = paymentDate;
        this.amount = amount;
        this.currency = currency == null ? "MAD" : currency;
    }

    public Long getId() { return id; }
    public String getTicker() { return ticker; }
    public LocalDate getExDate() { return exDate; }
    public LocalDate getPaymentDate() { return paymentDate; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
}
