package com.quantpulse.common.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Nested
    @DisplayName("why not double")
    class WhyNotDouble {

        @Test
        void doubleArithmeticIsWrongForMoney() {
            // This is why Money exists: 0.1 can't be stored exactly in a double.
            assertThat(0.1 + 0.2).isNotEqualTo(0.3);
            assertThat(Money.mad("0.1").plus(Money.mad("0.2")))
                    .isEqualTo(Money.mad("0.3"));
        }

        @Test
        void bigDecimalFromDoubleCarriesTheErrorIn() {
            // new BigDecimal(double) keeps the double's error. Use the String or valueOf constructors.
            assertThat(new BigDecimal(0.1).toPlainString()).startsWith("0.1000000000000000055511151231");
            assertThat(BigDecimal.valueOf(0.1).toPlainString()).isEqualTo("0.1");
        }
    }

    @Nested
    @DisplayName("scale normalisation")
    class ScaleNormalisation {

        @Test
        void equalityIsValueBasedNotScaleBased() {
            // Plain BigDecimal.equals() also compares scale:
            assertThat(new BigDecimal("10.0")).isNotEqualTo(new BigDecimal("10.00"));
            // Money normalises the scale, so these are equal.
            assertThat(Money.mad("10.0")).isEqualTo(Money.mad("10.00"));
            assertThat(Money.mad("10.0")).hasSameHashCodeAs(Money.mad("10.000000"));
        }

        @Test
        void allAmountsAreStoredAtScaleSix() {
            assertThat(Money.mad("683").amount().scale()).isEqualTo(Money.SCALE);
            assertThat(Money.mad("683").amount().toPlainString()).isEqualTo("683.000000");
        }
    }

    @Nested
    @DisplayName("banker's rounding")
    class BankersRounding {

        @Test
        void tiesRoundToTheNearestEvenDigit() {
            // HALF_UP would give 0.13 and 0.14, always rounding ties up.
            // HALF_EVEN goes to the even digit, so ties balance out.
            assertThat(Money.mad("0.125").forDisplay(2)).isEqualTo(new BigDecimal("0.12"));
            assertThat(Money.mad("0.135").forDisplay(2)).isEqualTo(new BigDecimal("0.14"));
        }

        @Test
        void divisionRoundsRatherThanThrowing() {
            // 1/3 never ends. BigDecimal.divide throws without a scale; Money always passes one.
            Money third = Money.mad("1").dividedBy(new BigDecimal("3"));
            assertThat(third.amount().toPlainString()).isEqualTo("0.333333");
        }
    }

    @Nested
    @DisplayName("currency safety")
    class CurrencySafety {

        @Test
        void mixingCurrenciesThrowsRatherThanGuessingAnFxRate() {
            assertThatThrownBy(() -> Money.mad("100").plus(Money.of("100", "USD")))
                    .isInstanceOf(Money.CurrencyMismatchException.class)
                    .hasMessageContaining("without an explicit FX rate");
        }

        @Test
        void rejectsNonIso4217Codes() {
            assertThatThrownBy(() -> Money.of("1", "DIRHAM"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void positionValueIsPriceTimesQuantity() {
            Money price = Money.mad("683.00");
            assertThat(price.times(150)).isEqualTo(Money.mad("102450"));
        }

        @Test
        void weightedAverageCostSurvivesIntermediateDivision() {
            // 100 @ 683 + 50 @ 700 = 103300, / 150 = 688.666667
            Money total = Money.mad("683").times(100).plus(Money.mad("700").times(50));
            assertThat(total).isEqualTo(Money.mad("103300"));
            assertThat(total.dividedBy(new BigDecimal("150")))
                    .isEqualTo(Money.mad("688.666667"));
        }

        @Test
        void signQueriesReadNaturally() {
            assertThat(Money.mad("-5").isNegative()).isTrue();
            assertThat(Money.zeroMad().isZero()).isTrue();
            assertThat(Money.mad("5").isGreaterThan(Money.mad("4.999999"))).isTrue();
        }
    }

    @Nested
    @DisplayName("currency conversion")
    class Conversion {

        @Test
        void convertsAtTheSuppliedRateIntoTheTargetCurrency() {
            Money usd = Money.of("250", "USD");
            Money mad = usd.convert(new BigDecimal("9.9512"), Money.MAD);
            assertThat(mad).isEqualTo(Money.mad("2487.80"));
            assertThat(mad.currency()).isEqualTo("MAD");
        }

        @Test
        void convertedAmountStillRefusesToMixWithTheSourceCurrency() {
            // After converting it's a different currency, it still can't be mixed.
            Money usd = Money.of("10", "USD");
            Money mad = usd.convert(new BigDecimal("9.95"), Money.MAD);
            assertThatThrownBy(() -> mad.plus(usd))
                    .isInstanceOf(Money.CurrencyMismatchException.class);
        }

        @Test
        void resultIsRoundedToScaleSixWithBankersRounding() {
            // 1 x 0.12345650 = 0.1234565, a tie at the 7th decimal, rounds to even
            Money eur = Money.of("1", "USD").convert(new BigDecimal("0.12345650"), "EUR");
            assertThat(eur.amount().toPlainString()).isEqualTo("0.123456");
        }

        @Test
        void roundTripThroughTheInverseRateReturnsTheOriginalWithinScale() {
            BigDecimal usdToMad = new BigDecimal("9.9512");
            BigDecimal madToUsd = BigDecimal.ONE.divide(usdToMad, 12, Money.ROUNDING);

            Money original = Money.of("1234.56", "USD");
            Money back = original.convert(usdToMad, Money.MAD).convert(madToUsd, "USD");

            // Not exactly equal because the inverse rate is rounded too.
            // The difference must stay under the stored precision.
            assertThat(back.minus(original).abs().amount())
                    .isLessThanOrEqualTo(new BigDecimal("0.000001"));
            assertThat(back.forDisplay(2)).isEqualTo(new BigDecimal("1234.56"));
        }

        @Test
        void rejectsANonPositiveRate() {
            assertThatThrownBy(() -> Money.of("1", "USD").convert(BigDecimal.ZERO, Money.MAD))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Money.of("1", "USD").convert(new BigDecimal("-9.95"), Money.MAD))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsConvertingIntoTheSameCurrency() {
            assertThatThrownBy(() -> Money.mad("100").convert(new BigDecimal("9.95"), Money.MAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already in MAD");
        }

        @Test
        void rejectsANonIsoTargetCurrency() {
            assertThatThrownBy(() -> Money.of("1", "USD").convert(new BigDecimal("9.95"), "DIRHAM"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
