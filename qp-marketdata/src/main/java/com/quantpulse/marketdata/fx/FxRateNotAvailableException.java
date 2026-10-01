package com.quantpulse.marketdata.fx;

import java.time.LocalDate;

/**
 * No rate for the pair on or before that date.
 * We throw instead of leaving the amount unconverted or guessing a rate.
 */
public class FxRateNotAvailableException extends RuntimeException {

    private final String baseCurrency;
    private final String quoteCurrency;
    private final LocalDate asOf;

    public FxRateNotAvailableException(String baseCurrency, String quoteCurrency, LocalDate asOf) {
        super("no " + baseCurrency + "/" + quoteCurrency + " rate on or before " + asOf);
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.asOf = asOf;
    }

    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public LocalDate getAsOf() { return asOf; }
}
