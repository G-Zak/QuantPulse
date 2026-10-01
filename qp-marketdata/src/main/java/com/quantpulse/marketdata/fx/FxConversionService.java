package com.quantpulse.marketdata.fx;

import com.quantpulse.common.money.Money;
import com.quantpulse.marketdata.domain.FxRate;
import com.quantpulse.marketdata.repository.FxRateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Converts Money between currencies with the rates we stored.
 *
 * Money.convert does the math, this class picks the rate and reads the DB.
 * It's here because fx_rate is in the market schema; qp-portfolio asks over HTTP.
 *
 * Only the direct pair is used: MAD to USD needs a stored MAD/USD rate.
 * We don't invert, so 1/rate rounding never sneaks in.
 */
@Service
public class FxConversionService {

    private final FxRateRepository rates;
    private final Clock clock;
    private final int staleAfterDays;

    public FxConversionService(FxRateRepository rates, Clock clock,
                               @Value("${quantpulse.alphavantage.fx-stale-after-days:4}") int staleAfterDays) {
        this.rates = rates;
        this.clock = clock;
        this.staleAfterDays = staleAfterDays;
    }

    /**
     * Latest rate for a pair, the one before it, and whether it's stale.
     * A stale rate is still returned with a flag, the caller decides what to do
     * (the portfolio page shows a badge).
     */
    public record Quote(String base, String quote, BigDecimal rate, LocalDate rateDate,
                        BigDecimal previousRate, LocalDate previousDate, BigDecimal changePercent,
                        String source, long ageDays, boolean stale) {
    }

    @Transactional(readOnly = true)
    public Optional<Quote> quote(String base, String quote) {
        List<FxRate> latest = rates.findTop2ByBaseCurrencyAndQuoteCurrencyOrderByRateDateDesc(base, quote);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        FxRate now = latest.get(0);
        FxRate prev = latest.size() > 1 ? latest.get(1) : null;
        BigDecimal change = prev == null ? null : now.getRate()
                .divide(prev.getRate(), MathContext.DECIMAL64)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100))
                .setScale(3, RoundingMode.HALF_EVEN);
        long age = ChronoUnit.DAYS.between(now.getRateDate(), LocalDate.now(clock));
        return Optional.of(new Quote(base, quote, now.getRate(), now.getRateDate(),
                prev == null ? null : prev.getRate(), prev == null ? null : prev.getRateDate(),
                change, now.getSource(), age, age > staleAfterDays));
    }

    /** Converts at the latest rate on or before today (UTC). */
    public Money convert(Money amount, String targetCurrency) {
        return convert(amount, targetCurrency, LocalDate.now(clock));
    }

    /**
     * Converts at the latest rate for the pair on or before asOf.
     *
     * @throws FxRateNotAvailableException if no rate was ever stored
     */
    @Transactional(readOnly = true)
    public Money convert(Money amount, String targetCurrency, LocalDate asOf) {
        FxRate rate = latestRate(amount.currency(), targetCurrency, asOf);
        return amount.convert(rate.getRate(), targetCurrency);
    }

    @Transactional(readOnly = true)
    public FxRate latestRate(String baseCurrency, String quoteCurrency, LocalDate asOf) {
        // Check this first: same currency is a caller bug, not a missing rate.
        if (baseCurrency.equals(quoteCurrency)) {
            throw new IllegalArgumentException("already in " + baseCurrency + "; no conversion needed");
        }
        return rates.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                        baseCurrency, quoteCurrency, asOf)
                .orElseThrow(() -> new FxRateNotAvailableException(baseCurrency, quoteCurrency, asOf));
    }
}
