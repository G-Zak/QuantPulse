package com.quantpulse.portfolio.domain;

import com.quantpulse.common.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostBasisTest {

    private static BigDecimal qty(String s) {
        return new BigDecimal(s);
    }

    private static final Money ZERO = Money.zeroMad();

    @Nested
    @DisplayName("weighted average")
    class WeightedAverage {

        @Test
        void buyAccumulatesCostAndQuantity() {
            var r = CostBasis.buyWeightedAverage(BigDecimal.ZERO, ZERO, ZERO,
                    qty("100"), Money.mad("683"), Money.mad("50"));

            assertThat(r.quantity()).isEqualByComparingTo("100");
            // 100 × 683 + 50 fee = 68,350
            assertThat(r.costBasis()).isEqualTo(Money.mad("68350"));
        }

        @Test
        void secondBuyBlendsTheUnitCost() {
            var first = CostBasis.buyWeightedAverage(BigDecimal.ZERO, ZERO, ZERO,
                    qty("100"), Money.mad("683"), ZERO);
            var second = CostBasis.buyWeightedAverage(first.quantity(), first.costBasis(),
                    first.realizedPnl(), qty("50"), Money.mad("700"), ZERO);

            assertThat(second.quantity()).isEqualByComparingTo("150");
            assertThat(second.costBasis()).isEqualTo(Money.mad("103300"));
            // 103,300 / 150 = 688.666667, the average cost kept at 6 decimals
            assertThat(CostBasis.unitCost(second.quantity(), second.costBasis()))
                    .isEqualTo(Money.mad("688.666667"));
        }

        @Test
        void sellRemovesCostProportionallyAndRealisesGain() {
            var held = CostBasis.buyWeightedAverage(BigDecimal.ZERO, ZERO, ZERO,
                    qty("100"), Money.mad("683"), ZERO);

            // Sell half at 700. Cost removed = 68,300 × (50/100) = 34,150.
            // Proceeds = 35,000. Realised = 850.
            var sold = CostBasis.sellWeightedAverage(held.quantity(), held.costBasis(),
                    held.realizedPnl(), qty("50"), Money.mad("700"), ZERO);

            assertThat(sold.quantity()).isEqualByComparingTo("50");
            assertThat(sold.costBasis()).isEqualTo(Money.mad("34150"));
            assertThat(sold.realizedPnl()).isEqualTo(Money.mad("850"));
        }

        @Test
        void feesReduceRealisedGainOnBothSides() {
            // Buy fee raises the cost, sell fee lowers the proceeds. Both reduce the gain.
            var held = CostBasis.buyWeightedAverage(BigDecimal.ZERO, ZERO, ZERO,
                    qty("100"), Money.mad("683"), Money.mad("100"));
            assertThat(held.costBasis()).isEqualTo(Money.mad("68400"));

            var sold = CostBasis.sellWeightedAverage(held.quantity(), held.costBasis(),
                    held.realizedPnl(), qty("100"), Money.mad("700"), Money.mad("100"));

            // Proceeds 70,000 − 100 = 69,900; cost 68,400; realised 1,500
            assertThat(sold.realizedPnl()).isEqualTo(Money.mad("1500"));
            assertThat(sold.quantity()).isEqualByComparingTo("0");
            assertThat(sold.costBasis()).isEqualTo(Money.zeroMad());
        }

        @Test
        void sellingMoreThanHeldIsRejected() {
            assertThatThrownBy(() -> CostBasis.sellWeightedAverage(
                    qty("10"), Money.mad("1000"), ZERO, qty("11"), Money.mad("100"), ZERO))
                    .isInstanceOf(CostBasis.InsufficientSharesException.class)
                    .hasMessageContaining("short selling is not supported");
        }
    }

    @Nested
    @DisplayName("FIFO")
    class Fifo {

        @Test
        void sellsTheOldestLotFirst() {
            var afterFirst = CostBasis.buyFifo(List.of(), ZERO, qty("100"), Money.mad("600"), ZERO);
            var afterSecond = CostBasis.buyFifo(afterFirst.lots(), afterFirst.realizedPnl(),
                    qty("100"), Money.mad("700"), ZERO);

            // Sell 100 at 800. FIFO consumes the 600 lot entirely: gain = 20,000.
            var sold = CostBasis.sellFifo(afterSecond.lots(), afterSecond.realizedPnl(),
                    qty("100"), Money.mad("800"), ZERO);

            assertThat(sold.realizedPnl()).isEqualTo(Money.mad("20000"));
            assertThat(sold.quantity()).isEqualByComparingTo("100");
            // Only the 700 lot remains
            assertThat(sold.lots()).hasSize(1);
            assertThat(sold.lots().get(0).unitCost()).isEqualTo(Money.mad("700"));
        }

        @Test
        void consumesAcrossMultipleLots() {
            var l1 = CostBasis.buyFifo(List.of(), ZERO, qty("50"), Money.mad("600"), ZERO);
            var l2 = CostBasis.buyFifo(l1.lots(), l1.realizedPnl(), qty("50"), Money.mad("700"), ZERO);

            // Sell 75: all 50 @600 plus 25 @700. Cost = 30,000 + 17,500 = 47,500.
            // Proceeds = 75 × 800 = 60,000. Realised = 12,500.
            var sold = CostBasis.sellFifo(l2.lots(), l2.realizedPnl(),
                    qty("75"), Money.mad("800"), ZERO);

            assertThat(sold.realizedPnl()).isEqualTo(Money.mad("12500"));
            assertThat(sold.quantity()).isEqualByComparingTo("25");
            assertThat(sold.lots()).hasSize(1);
            assertThat(sold.lots().get(0).quantity()).isEqualByComparingTo("25");
        }

        @Test
        @DisplayName("FIFO and weighted average give DIFFERENT realised P&L — not a rounding difference")
        void differsFromWeightedAverage() {
            // Same trades both ways: buy 100@600, buy 100@700, sell 100@800.
            var fifoA = CostBasis.buyFifo(List.of(), ZERO, qty("100"), Money.mad("600"), ZERO);
            var fifoB = CostBasis.buyFifo(fifoA.lots(), fifoA.realizedPnl(),
                    qty("100"), Money.mad("700"), ZERO);
            var fifoSold = CostBasis.sellFifo(fifoB.lots(), fifoB.realizedPnl(),
                    qty("100"), Money.mad("800"), ZERO);

            var wa1 = CostBasis.buyWeightedAverage(BigDecimal.ZERO, ZERO, ZERO,
                    qty("100"), Money.mad("600"), ZERO);
            var wa2 = CostBasis.buyWeightedAverage(wa1.quantity(), wa1.costBasis(),
                    wa1.realizedPnl(), qty("100"), Money.mad("700"), ZERO);
            var waSold = CostBasis.sellWeightedAverage(wa2.quantity(), wa2.costBasis(),
                    wa2.realizedPnl(), qty("100"), Money.mad("800"), ZERO);

            // FIFO: sold the 600 lot     -> 80,000 − 60,000 = 20,000
            // WA:   blended cost of 650  -> 80,000 − 65,000 = 15,000
            assertThat(fifoSold.realizedPnl()).isEqualTo(Money.mad("20000"));
            assertThat(waSold.realizedPnl()).isEqualTo(Money.mad("15000"));
            assertThat(fifoSold.realizedPnl()).isNotEqualTo(waSold.realizedPnl());

            // Both still hold the same 100 shares. Only the split between realised and
            // unrealised is different.
            assertThat(fifoSold.quantity()).isEqualByComparingTo(waSold.quantity());
        }

        @Test
        void feesAreFoldedIntoTheLotUnitCost() {
            // 100 shares @683 with a 100 fee -> effective unit cost 684
            var r = CostBasis.buyFifo(List.of(), ZERO, qty("100"), Money.mad("683"), Money.mad("100"));
            assertThat(r.lots().get(0).unitCost()).isEqualTo(Money.mad("684"));
        }

        @Test
        void sellingMoreThanHeldIsRejected() {
            var lots = CostBasis.buyFifo(List.of(), ZERO, qty("10"), Money.mad("100"), ZERO);
            assertThatThrownBy(() -> CostBasis.sellFifo(lots.lots(), ZERO,
                    qty("11"), Money.mad("100"), ZERO))
                    .isInstanceOf(CostBasis.InsufficientSharesException.class);
        }
    }

    @Nested
    @DisplayName("unrealised P&L")
    class Unrealised {

        @Test
        void isMarketValueMinusCost() {
            var held = CostBasis.buyWeightedAverage(BigDecimal.ZERO, ZERO, ZERO,
                    qty("100"), Money.mad("683"), ZERO);
            assertThat(CostBasis.unrealizedPnl(held.quantity(), held.costBasis(), Money.mad("700")))
                    .isEqualTo(Money.mad("1700"));
        }

        @Test
        void isNegativeWhenUnderwater() {
            var held = CostBasis.buyWeightedAverage(BigDecimal.ZERO, ZERO, ZERO,
                    qty("100"), Money.mad("683"), ZERO);
            assertThat(CostBasis.unrealizedPnl(held.quantity(), held.costBasis(), Money.mad("600"))
                    .isNegative()).isTrue();
        }

        @Test
        void unitCostOfAnEmptyPositionIsZeroNotAnException() {
            // Guards the divide-by-zero on a fully closed position.
            assertThat(CostBasis.unitCost(BigDecimal.ZERO, Money.zeroMad()))
                    .isEqualTo(Money.zeroMad());
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void aFullBuyThenSellLeavesNothingHeld() {
            var bought = CostBasis.buyWeightedAverage(BigDecimal.ZERO, ZERO, ZERO,
                    qty("250"), Money.mad("93.65"), Money.mad("25"));
            var sold = CostBasis.sellWeightedAverage(bought.quantity(), bought.costBasis(),
                    bought.realizedPnl(), qty("250"), Money.mad("95.00"), Money.mad("25"));

            assertThat(sold.quantity()).isEqualByComparingTo("0");
            assertThat(sold.costBasis()).isEqualTo(Money.zeroMad());
            // Bought 23,412.50 + 25 = 23,437.50; sold 23,750 − 25 = 23,725. Gain 287.50.
            assertThat(sold.realizedPnl()).isEqualTo(Money.mad("287.50"));
        }
    }
}
