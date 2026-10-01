package com.quantpulse.common.money;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount in a currency.
 *
 * - BigDecimal, never double (0.1 + 0.2 != 0.3 in double).
 * - Always 6 decimals, because BigDecimal.equals("10.0", "10.00") is false.
 * - HALF_EVEN rounding, so rounding errors cancel out instead of adding up.
 *
 * 6 decimals leaves room for divisions like average cost per share.
 * Prices on the Casablanca exchange only have 2.
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money>, Serializable {

    public static final int SCALE = 6;

    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public static final String MAD = "MAD";

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be an ISO-4217 alpha-3 code: " + currency);
        }
        // Normalise the scale so equals/hashCode compare values.
        amount = amount.setScale(SCALE, ROUNDING);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money mad(BigDecimal amount) {
        return new Money(amount, MAD);
    }

    public static Money mad(String amount) {
        return new Money(new BigDecimal(amount), MAD);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money zeroMad() {
        return zero(MAD);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /** Multiply by a plain number, e.g. price x shares. */
    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money times(long factor) {
        return times(BigDecimal.valueOf(factor));
    }

    /**
     * Divide by a plain number, e.g. total cost / shares.
     * Scale and rounding are given explicitly because division can go on forever (1/3).
     */
    public Money dividedBy(BigDecimal divisor) {
        if (divisor.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        return new Money(amount.divide(divisor, SCALE, ROUNDING), currency);
    }

    /**
     * Converts to another currency. rate = units of targetCurrency for 1 unit of this one
     * (USD to MAD at 9.95 means 1 USD = 9.95 MAD).
     *
     * The caller picks the rate, so this stays pure and easy to test.
     * Same-currency conversion throws, because it means the caller picked the wrong rate.
     */
    public Money convert(BigDecimal rate, String targetCurrency) {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(targetCurrency, "targetCurrency");
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("FX rate must be positive: " + rate);
        }
        if (currency.equals(targetCurrency)) {
            throw new IllegalArgumentException("already in " + currency + "; no conversion needed");
        }
        return new Money(amount.multiply(rate), targetCurrency);
    }

    public Money negated() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    @JsonIgnore
    public boolean isZero() {
        return amount.signum() == 0;
    }

    @JsonIgnore
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @JsonIgnore
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    /** Rounded for display only. Don't use the result in calculations. */
    public BigDecimal forDisplay(int decimals) {
        return amount.setScale(decimals, ROUNDING);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.setScale(2, ROUNDING).toPlainString() + " " + currency;
    }

    /** Mixing currencies without a rate is always a bug. */
    public static class CurrencyMismatchException extends RuntimeException {
        public CurrencyMismatchException(String left, String right) {
            super("cannot combine " + left + " with " + right + " without an explicit FX rate");
        }
    }
}
