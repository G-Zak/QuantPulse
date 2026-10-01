package com.quantpulse.alerts.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * An alert rule set by a user. The evaluation logic is here because it only uses
 * this rule's own fields, and it's easy to unit test.
 *
 * The rule fires when the price crosses the threshold, not every time it's above it.
 * Otherwise a price sitting just above the line would alert every 15 minutes.
 *
 * - Hysteresis: after firing, the rule only re-arms once the price goes back
 *   past the threshold by hysteresisPct.
 * - Cooldown: a minimum time between two firings, for a price that keeps moving.
 */
@Entity
@Table(name = "alert_rule")
public class AlertRule {

    public enum Type {
        PRICE_ABOVE, PRICE_BELOW,
        PERCENT_MOVE_UP, PERCENT_MOVE_DOWN,
        DRAWDOWN
    }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(nullable = false, length = 16)
    private String ticker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal threshold;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "hysteresis_pct", nullable = false, precision = 9, scale = 4)
    private BigDecimal hysteresisPct = new BigDecimal("0.5");

    @Column(name = "cooldown_seconds", nullable = false)
    private int cooldownSeconds = 3600;

    @Column(nullable = false)
    private boolean armed = true;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Column(name = "last_price", precision = 19, scale = 6)
    private BigDecimal lastPrice;

    @Column(name = "fire_count", nullable = false)
    private int fireCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AlertRule() {
    }

    public AlertRule(String owner, String ticker, Type type, BigDecimal threshold) {
        this.owner = owner;
        this.ticker = ticker;
        this.type = type;
        this.threshold = threshold;
    }

    /** Why the rule fired, saved when it fires. */
    public record Trigger(BigDecimal price, BigDecimal threshold, String reason) {
    }

    /**
     * Checks a new price against this rule.
     *
     * @param referencePrice previous close, used by the percent rules (can be null)
     * @return the trigger if the rule fires, otherwise empty
     */
    public Optional<Trigger> evaluate(BigDecimal price, BigDecimal referencePrice, Instant now) {
        if (!enabled || price == null) {
            return Optional.empty();
        }

        boolean conditionMet = switch (type) {
            case PRICE_ABOVE -> price.compareTo(threshold) > 0;
            case PRICE_BELOW -> price.compareTo(threshold) < 0;
            case PERCENT_MOVE_UP -> percentMove(price, referencePrice)
                    .map(m -> m.compareTo(threshold) >= 0).orElse(false);
            case PERCENT_MOVE_DOWN -> percentMove(price, referencePrice)
                    .map(m -> m.compareTo(threshold.negate()) <= 0).orElse(false);
            case DRAWDOWN -> percentMove(price, referencePrice)
                    .map(m -> m.compareTo(threshold.negate()) <= 0).orElse(false);
        };

        // Re-arm first, so the rule can fire again on this same tick.
        if (!armed && hasRetreated(price)) {
            armed = true;
        }

        this.lastPrice = price;

        if (!conditionMet || !armed || inCooldown(now)) {
            return Optional.empty();
        }

        armed = false;
        lastFiredAt = now;
        fireCount++;
        return Optional.of(new Trigger(price, threshold, describe(price, referencePrice)));
    }

    /**
     * Has the price gone back past the threshold by more than the hysteresis margin?
     * Example: PRICE_ABOVE 700 with 0.5% only re-arms below 696.50.
     */
    private boolean hasRetreated(BigDecimal price) {
        BigDecimal margin = threshold.multiply(hysteresisPct)
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_EVEN);
        return switch (type) {
            case PRICE_ABOVE -> price.compareTo(threshold.subtract(margin)) < 0;
            case PRICE_BELOW -> price.compareTo(threshold.add(margin)) > 0;
            // Percent rules compare to the previous close, so a flat price doesn't re-fire.
            // The cooldown is enough for them.
            case PERCENT_MOVE_UP, PERCENT_MOVE_DOWN, DRAWDOWN -> true;
        };
    }

    private boolean inCooldown(Instant now) {
        return lastFiredAt != null
                && Duration.between(lastFiredAt, now).getSeconds() < cooldownSeconds;
    }

    private Optional<BigDecimal> percentMove(BigDecimal price, BigDecimal reference) {
        if (reference == null || reference.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(price.subtract(reference)
                .divide(reference, 6, RoundingMode.HALF_EVEN)
                .multiply(BigDecimal.valueOf(100)));
    }

    private String describe(BigDecimal price, BigDecimal reference) {
        return switch (type) {
            case PRICE_ABOVE -> "%s rose to %s, above the %s threshold"
                    .formatted(ticker, plain(price), plain(threshold));
            case PRICE_BELOW -> "%s fell to %s, below the %s threshold"
                    .formatted(ticker, plain(price), plain(threshold));
            case PERCENT_MOVE_UP -> "%s gained %s%% to %s (threshold %s%%)".formatted(
                    ticker, percentMove(price, reference).map(this::plain).orElse("?"),
                    plain(price), plain(threshold));
            case PERCENT_MOVE_DOWN, DRAWDOWN -> "%s dropped %s%% to %s (threshold %s%%)".formatted(
                    ticker, percentMove(price, reference).map(this::plain).orElse("?"),
                    plain(price), plain(threshold));
        };
    }

    private String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    public UUID getId() { return id; }
    public String getOwner() { return owner; }
    public String getTicker() { return ticker; }
    public Type getType() { return type; }
    public BigDecimal getThreshold() { return threshold; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isArmed() { return armed; }
    public Instant getLastFiredAt() { return lastFiredAt; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public int getFireCount() { return fireCount; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    public BigDecimal getHysteresisPct() { return hysteresisPct; }
    public void setHysteresisPct(BigDecimal hysteresisPct) { this.hysteresisPct = hysteresisPct; }
    public Instant getCreatedAt() { return createdAt; }
}
