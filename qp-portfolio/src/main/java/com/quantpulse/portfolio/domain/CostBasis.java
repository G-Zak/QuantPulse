package com.quantpulse.portfolio.domain;

import com.quantpulse.common.money.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Cost basis and realised P&amp;L, as pure functions (no DB, no clock, no Spring),
 * so they're easy to test.
 *
 * Weighted average: one average cost for all shares held. What most brokers show.
 * FIFO: keeps each purchase lot and sells the oldest first.
 * The two give different realised P&amp;L on the same trades, so it's a setting.
 *
 * Buy fees add to the cost, sell fees reduce the proceeds.
 */
public final class CostBasis {

    private CostBasis() {
    }

    /** Shares bought at one price, for FIFO. */
    public record Lot(BigDecimal quantity, Money unitCost) {

        public Money totalCost() {
            return unitCost.times(quantity);
        }

        public Lot reduceBy(BigDecimal sold) {
            return new Lot(quantity.subtract(sold), unitCost);
        }
    }

    /** Result of one transaction. */
    public record Result(BigDecimal quantity, Money costBasis, Money realizedPnl, List<Lot> lots) {
    }

    // Weighted average

    /**
     * Buy (weighted average). Just add to the total cost, we only divide when someone
     * asks for the unit cost.
     */
    public static Result buyWeightedAverage(BigDecimal heldQty, Money heldCost,
                                            Money realizedSoFar,
                                            BigDecimal buyQty, Money unitPrice, Money fees) {
        BigDecimal newQty = heldQty.add(buyQty);
        Money newCost = heldCost.plus(unitPrice.times(buyQty)).plus(fees);
        return new Result(newQty, newCost, realizedSoFar, List.of());
    }

    /**
     * Sell (weighted average). Remove cost in proportion to the shares sold.
     * Realised P&amp;L = proceeds - removed cost - sell fee.
     *
     * @throws InsufficientSharesException if selling more than held (no short selling)
     */
    public static Result sellWeightedAverage(BigDecimal heldQty, Money heldCost,
                                             Money realizedSoFar,
                                             BigDecimal sellQty, Money unitPrice, Money fees) {
        if (sellQty.compareTo(heldQty) > 0) {
            throw new InsufficientSharesException(heldQty, sellQty);
        }

        Money proceeds = unitPrice.times(sellQty).minus(fees);

        // Proportional cost. heldQty is only 0 if sellQty is 0, but check anyway.
        Money costRemoved = heldQty.signum() == 0
                ? Money.zero(heldCost.currency())
                : heldCost.times(sellQty).dividedBy(heldQty);

        return new Result(
                heldQty.subtract(sellQty),
                heldCost.minus(costRemoved),
                realizedSoFar.plus(proceeds.minus(costRemoved)),
                List.of());
    }

    // FIFO

    /** Buy (FIFO): add a new lot. */
    public static Result buyFifo(List<Lot> lots, Money realizedSoFar,
                                 BigDecimal buyQty, Money unitPrice, Money fees) {
        // Fees go into the lot's unit cost, so per-lot P&L is right.
        Money effectiveUnitCost = buyQty.signum() == 0
                ? unitPrice
                : unitPrice.plus(fees.dividedBy(buyQty));

        List<Lot> updated = new ArrayList<>(lots);
        updated.add(new Lot(buyQty, effectiveUnitCost));
        return new Result(totalQuantity(updated), totalCost(updated, unitPrice.currency()),
                realizedSoFar, updated);
    }

    /**
     * Sell (FIFO): use the oldest lots first. Lots must be in purchase order.
     * Gain = proceeds - cost of the shares actually sold.
     */
    public static Result sellFifo(List<Lot> lots, Money realizedSoFar,
                                  BigDecimal sellQty, Money unitPrice, Money fees) {
        BigDecimal available = totalQuantity(lots);
        if (sellQty.compareTo(available) > 0) {
            throw new InsufficientSharesException(available, sellQty);
        }

        String currency = unitPrice.currency();
        Money proceeds = unitPrice.times(sellQty).minus(fees);
        Money costOfSold = Money.zero(currency);
        BigDecimal remainingToSell = sellQty;
        List<Lot> updated = new ArrayList<>();

        for (Lot lot : lots) {
            if (remainingToSell.signum() <= 0) {
                updated.add(lot);
                continue;
            }
            BigDecimal takeFromLot = lot.quantity().min(remainingToSell);
            costOfSold = costOfSold.plus(lot.unitCost().times(takeFromLot));
            remainingToSell = remainingToSell.subtract(takeFromLot);

            Lot reduced = lot.reduceBy(takeFromLot);
            if (reduced.quantity().signum() > 0) {
                updated.add(reduced);
            }
        }

        return new Result(totalQuantity(updated), totalCost(updated, currency),
                realizedSoFar.plus(proceeds.minus(costOfSold)), updated);
    }

    // Helpers

    public static BigDecimal totalQuantity(List<Lot> lots) {
        return lots.stream().map(Lot::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static Money totalCost(List<Lot> lots, String currency) {
        return lots.stream().map(Lot::totalCost)
                .reduce(Money.zero(currency), Money::plus);
    }

    /** Average cost per share, for display only. Don't use it in the ledger (precision loss). */
    public static Money unitCost(BigDecimal quantity, Money costBasis) {
        return quantity.signum() == 0 ? Money.zero(costBasis.currency()) : costBasis.dividedBy(quantity);
    }

    /** Unrealised P&amp;L at a given price. */
    public static Money unrealizedPnl(BigDecimal quantity, Money costBasis, Money marketPrice) {
        return marketPrice.times(quantity).minus(costBasis);
    }

    public static class InsufficientSharesException extends RuntimeException {
        public InsufficientSharesException(BigDecimal held, BigDecimal requested) {
            super("cannot sell " + requested.toPlainString() + " shares, only "
                    + held.toPlainString() + " held (short selling is not supported)");
        }
    }
}
