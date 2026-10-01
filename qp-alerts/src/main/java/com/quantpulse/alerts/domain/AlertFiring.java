package com.quantpulse.alerts.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alert_firing")
public class AlertFiring {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Column(name = "triggered_price", nullable = false, precision = 19, scale = 6)
    private BigDecimal triggeredPrice;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal threshold;

    @Column(nullable = false)
    private String reason;

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt = Instant.now();

    protected AlertFiring() {
    }

    public AlertFiring(UUID ruleId, String ticker, BigDecimal triggeredPrice,
                       BigDecimal threshold, String reason) {
        this.ruleId = ruleId;
        this.ticker = ticker;
        this.triggeredPrice = triggeredPrice;
        this.threshold = threshold;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public UUID getRuleId() { return ruleId; }
    public String getTicker() { return ticker; }
    public BigDecimal getTriggeredPrice() { return triggeredPrice; }
    public BigDecimal getThreshold() { return threshold; }
    public String getReason() { return reason; }
    public Instant getFiredAt() { return firedAt; }
}
