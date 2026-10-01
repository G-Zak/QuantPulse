package com.quantpulse.marketdata.fx;

import com.quantpulse.common.money.Money;
import com.quantpulse.marketdata.domain.FxRate;
import com.quantpulse.marketdata.repository.FxRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FxConversionServiceTest {

    // Monday 2026-09-28; the last stored USD/MAD rate is Friday's.
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 25);

    private FxRateRepository rates;
    private FxConversionService service;

    @BeforeEach
    void setUp() {
        rates = mock(FxRateRepository.class);
        Clock monday = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneOffset.UTC);
        service = new FxConversionService(rates, monday, 4);
    }

    private void givenLatest(String base, String quote, LocalDate asOf, FxRate rate) {
        when(rates.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                base, quote, asOf)).thenReturn(Optional.ofNullable(rate));
    }

    @Nested
    @DisplayName("latest rate as of a date")
    class LatestAsOf {

        @Test
        void usesFridaysRateForAMondayConversionWhenNothingNewerExists() {
            givenLatest("USD", "MAD", MONDAY, new FxRate("USD", "MAD", FRIDAY, new BigDecimal("9.184500"), "FIXTURE"));

            Money converted = service.convert(Money.of("100", "USD"), Money.MAD);

            assertThat(converted).isEqualTo(Money.mad("918.45"));
        }

        @Test
        void defaultsTheAsOfDateToTodayOnTheInjectedClock() {
            givenLatest("USD", "MAD", MONDAY, new FxRate("USD", "MAD", MONDAY, new BigDecimal("9.2"), "FIXTURE"));

            service.convert(Money.of("1", "USD"), Money.MAD);

            verify(rates).findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                    "USD", "MAD", MONDAY);
        }

        @Test
        void aHistoricalConversionAsksForTheRateAsOfThatDateNotToday() {
            LocalDate midYear = LocalDate.of(2026, 6, 30);
            givenLatest("EUR", "MAD", midYear, new FxRate("EUR", "MAD", midYear, new BigDecimal("10.5"), "FIXTURE"));

            Money converted = service.convert(Money.of("2", "EUR"), Money.MAD, midYear);

            assertThat(converted).isEqualTo(Money.mad("21"));
            verify(rates, never()).findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                    eq("EUR"), eq("MAD"), eq(MONDAY));
        }
    }

    @Nested
    @DisplayName("quotes")
    class Quotes {

        @Test
        void reportsDayOverDayChangeAndFlagsARateOlderThanTheThreshold() {
            when(rates.findTop2ByBaseCurrencyAndQuoteCurrencyOrderByRateDateDesc("USD", "MAD")).thenReturn(java.util.List.of(
                    new FxRate("USD", "MAD", LocalDate.of(2026, 9, 22), new BigDecimal("9.200000"), "ALPHAVANTAGE"),
                    new FxRate("USD", "MAD", LocalDate.of(2026, 9, 21), new BigDecimal("9.100000"), "ALPHAVANTAGE")));

            var q = service.quote("USD", "MAD").orElseThrow();

            assertThat(q.changePercent()).isEqualByComparingTo("1.099");
            assertThat(q.ageDays()).isEqualTo(6);
            assertThat(q.stale()).isTrue();
        }

        @Test
        void aFridayRateOnMondayIsNotStale() {
            when(rates.findTop2ByBaseCurrencyAndQuoteCurrencyOrderByRateDateDesc("EUR", "MAD")).thenReturn(java.util.List.of(
                    new FxRate("EUR", "MAD", FRIDAY, new BigDecimal("10.7"), "ALPHAVANTAGE")));

            var q = service.quote("EUR", "MAD").orElseThrow();

            assertThat(q.stale()).isFalse();
            assertThat(q.changePercent()).isNull();
        }
    }

    @Nested
    @DisplayName("no rate available")
    class NoRate {

        @Test
        void throwsNamingThePairAndDateInsteadOfReturningTheAmountUnconverted() {
            givenLatest("USD", "MAD", MONDAY, null);

            assertThatThrownBy(() -> service.convert(Money.of("100", "USD"), Money.MAD))
                    .isInstanceOf(FxRateNotAvailableException.class)
                    .hasMessageContaining("USD/MAD")
                    .hasMessageContaining("2026-09-28");
        }

        @Test
        void sameCurrencyIsRejectedAsACallerErrorBeforeAnyLookup() {
            assertThatThrownBy(() -> service.convert(Money.mad("100"), Money.MAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already in MAD");
            verify(rates, never()).findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                    any(), any(), any());
        }

        @Test
        void doesNotFallBackToTheInversePair() {
            // Only USD/MAD is stored; MAD->USD must not be inferred as 1/rate.
            when(rates.findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
                    eq("MAD"), eq("USD"), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.convert(Money.mad("100"), "USD"))
                    .isInstanceOf(FxRateNotAvailableException.class)
                    .hasMessageContaining("MAD/USD");
        }
    }
}
