package com.quantpulse.portfolio.service;

import com.quantpulse.common.money.Money;
import com.quantpulse.portfolio.domain.CostBasis;
import com.quantpulse.portfolio.domain.Position;
import com.quantpulse.portfolio.domain.TaxLot;
import com.quantpulse.portfolio.domain.TradeTransaction;
import com.quantpulse.portfolio.repository.PositionRepository;
import com.quantpulse.portfolio.repository.TaxLotRepository;
import com.quantpulse.portfolio.repository.TradeTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Records trades and updates positions, in one transaction so they always match. */
@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    /**
     * Cost basis method (config). WEIGHTED_AVERAGE and FIFO give different realised P&amp;L,
     * and tax rules usually require one of them.
     */
    public enum Method { WEIGHTED_AVERAGE, FIFO }

    private final TradeTransactionRepository transactions;
    private final PositionRepository positions;
    private final TaxLotRepository lots;
    private final Method method;

    public TradeService(TradeTransactionRepository transactions,
                        PositionRepository positions,
                        TaxLotRepository lots,
                        @Value("${quantpulse.portfolio.cost-basis-method:WEIGHTED_AVERAGE}") Method method) {
        this.transactions = transactions;
        this.positions = positions;
        this.lots = lots;
        this.method = method;
    }

    public Method method() {
        return method;
    }

    @Transactional
    public TradeTransaction recordBuy(UUID portfolioId, String ticker, BigDecimal quantity,
                                      Money price, Money fees, Instant executedAt, String note) {
        TradeTransaction txn = new TradeTransaction(portfolioId, ticker, TradeTransaction.Type.BUY,
                quantity, price, fees, executedAt, note);
        transactions.save(txn);

        Position position = loadOrCreate(portfolioId, ticker, price.currency());

        if (method == Method.FIFO) {
            // Fees go into the lot's unit cost.
            Money unitCostWithFees = quantity.signum() == 0
                    ? price : price.plus(fees.dividedBy(quantity));
            lots.save(new TaxLot(portfolioId, ticker, executedAt, quantity, unitCostWithFees));
            recomputeFromLots(position, portfolioId, ticker, price.currency());
        } else {
            CostBasis.Result r = CostBasis.buyWeightedAverage(
                    position.getQuantity(), position.costBasisMoney(), position.realizedPnlMoney(),
                    quantity, price, fees);
            position.apply(r.quantity(), r.costBasis(), r.realizedPnl());
        }

        positions.save(position);
        log.info("[TRADE] BUY {} x{} @ {} ({})", ticker, quantity.toPlainString(), price, method);
        return txn;
    }

    @Transactional
    public TradeTransaction recordSell(UUID portfolioId, String ticker, BigDecimal quantity,
                                       Money price, Money fees, Instant executedAt, String note) {
        Position position = positions.findByPortfolioIdAndTicker(portfolioId, ticker)
                .orElseThrow(() -> new CostBasis.InsufficientSharesException(BigDecimal.ZERO, quantity));

        TradeTransaction txn = new TradeTransaction(portfolioId, ticker, TradeTransaction.Type.SELL,
                quantity, price, fees, executedAt, note);

        if (method == Method.FIFO) {
            sellFifo(portfolioId, ticker, quantity, price, fees, position);
        } else {
            CostBasis.Result r = CostBasis.sellWeightedAverage(
                    position.getQuantity(), position.costBasisMoney(), position.realizedPnlMoney(),
                    quantity, price, fees);
            position.apply(r.quantity(), r.costBasis(), r.realizedPnl());
        }

        // Saved only after the cost basis works. Selling too many shares throws and rolls back,
        // so no ledger row is left for a rejected trade.
        transactions.save(txn);
        positions.save(position);
        log.info("[TRADE] SELL {} x{} @ {} realized={}",
                ticker, quantity.toPlainString(), price, position.realizedPnlMoney());
        return txn;
    }

    /** A cash dividend, recorded as income (not as a lower cost basis). */
    @Transactional
    public TradeTransaction recordDividend(UUID portfolioId, String ticker, BigDecimal quantity,
                                           Money amountPerShare, Instant executedAt) {
        TradeTransaction txn = new TradeTransaction(portfolioId, ticker,
                TradeTransaction.Type.DIVIDEND, quantity, amountPerShare,
                Money.zero(amountPerShare.currency()), executedAt, "dividend");
        transactions.save(txn);

        Position position = loadOrCreate(portfolioId, ticker, amountPerShare.currency());
        position.apply(position.getQuantity(), position.costBasisMoney(),
                position.realizedPnlMoney().plus(amountPerShare.times(quantity)));
        positions.save(position);
        return txn;
    }

    private void sellFifo(UUID portfolioId, String ticker, BigDecimal quantity,
                          Money price, Money fees, Position position) {
        List<TaxLot> openLots = lots.findOpenLots(portfolioId, ticker);
        BigDecimal available = openLots.stream()
                .map(TaxLot::getRemainingQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (quantity.compareTo(available) > 0) {
            throw new CostBasis.InsufficientSharesException(available, quantity);
        }

        Money proceeds = price.times(quantity).minus(fees);
        Money costOfSold = Money.zero(price.currency());
        BigDecimal remaining = quantity;

        for (TaxLot lot : openLots) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = lot.getRemainingQuantity().min(remaining);
            costOfSold = costOfSold.plus(lot.unitCostMoney().times(take));
            lot.consume(take);
            lots.save(lot);
            remaining = remaining.subtract(take);
        }

        Money realized = position.realizedPnlMoney().plus(proceeds.minus(costOfSold));
        recomputeFromLots(position, portfolioId, ticker, price.currency());
        position.apply(position.getQuantity(), position.costBasisMoney(), realized);
    }

    private void recomputeFromLots(Position position, UUID portfolioId, String ticker, String currency) {
        List<TaxLot> open = lots.findOpenLots(portfolioId, ticker);
        BigDecimal qty = open.stream()
                .map(TaxLot::getRemainingQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        Money cost = open.stream()
                .map(l -> l.unitCostMoney().times(l.getRemainingQuantity()))
                .reduce(Money.zero(currency), Money::plus);
        position.apply(qty, cost, position.realizedPnlMoney());
    }

    private Position loadOrCreate(UUID portfolioId, String ticker, String currency) {
        return positions.findByPortfolioIdAndTicker(portfolioId, ticker)
                .orElseGet(() -> new Position(portfolioId, ticker, currency));
    }

    /**
     * Rebuilds a position by replaying its ledger.
     * If the result doesn't match the stored position, the stored one is wrong.
     */
    @Transactional
    public Position rebuild(UUID portfolioId, String ticker) {
        List<TradeTransaction> ledger =
                transactions.findByPortfolioIdAndTickerOrderByExecutedAtAscIdAsc(portfolioId, ticker);

        BigDecimal qty = BigDecimal.ZERO;
        Money cost = Money.zeroMad();
        Money realized = Money.zeroMad();

        for (TradeTransaction t : ledger) {
            switch (t.getType()) {
                case BUY -> {
                    CostBasis.Result r = CostBasis.buyWeightedAverage(qty, cost, realized,
                            t.getQuantity(), t.priceMoney(), t.feesMoney());
                    qty = r.quantity();
                    cost = r.costBasis();
                    realized = r.realizedPnl();
                }
                case SELL -> {
                    CostBasis.Result r = CostBasis.sellWeightedAverage(qty, cost, realized,
                            t.getQuantity(), t.priceMoney(), t.feesMoney());
                    qty = r.quantity();
                    cost = r.costBasis();
                    realized = r.realizedPnl();
                }
                case DIVIDEND -> realized = realized.plus(t.priceMoney().times(t.getQuantity()));
                case SPLIT -> qty = qty.multiply(t.getQuantity());
            }
        }

        Position position = loadOrCreate(portfolioId, ticker, cost.currency());
        position.apply(qty, cost, realized);
        return positions.save(position);
    }
}
