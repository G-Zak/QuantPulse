package com.quantpulse.portfolio.api;

import com.quantpulse.common.money.Money;
import com.quantpulse.portfolio.domain.CostBasis;
import com.quantpulse.portfolio.domain.Portfolio;
import com.quantpulse.portfolio.domain.Position;
import com.quantpulse.portfolio.domain.TradeTransaction;
import com.quantpulse.portfolio.repository.PortfolioRepository;
import com.quantpulse.portfolio.repository.PositionRepository;
import com.quantpulse.portfolio.repository.TradeTransactionRepository;
import com.quantpulse.portfolio.service.FxRateClient;
import com.quantpulse.portfolio.service.RiskService;
import com.quantpulse.portfolio.service.TradeService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/portfolios")
public class PortfolioController {

    private final PortfolioRepository portfolios;
    private final PositionRepository positions;
    private final TradeTransactionRepository transactions;
    private final TradeService trades;
    private final RiskService risk;
    private final FxRateClient fx;

    public PortfolioController(PortfolioRepository portfolios, PositionRepository positions,
                               TradeTransactionRepository transactions,
                               TradeService trades, RiskService risk, FxRateClient fx) {
        this.portfolios = portfolios;
        this.positions = positions;
        this.transactions = transactions;
        this.trades = trades;
        this.risk = risk;
        this.fx = fx;
    }

    // Requests

    public record CreatePortfolioRequest(@NotNull String owner, @NotNull String name,
                                         String baseCurrency) {
    }

    public record TradeRequest(@NotNull String ticker,
                               @NotNull @Positive BigDecimal quantity,
                               @NotNull @Positive BigDecimal price,
                               BigDecimal fees,
                               Instant executedAt,
                               String note) {
    }

    // Views

    public record PositionView(String ticker, BigDecimal quantity, BigDecimal costBasis,
                               BigDecimal unitCost, BigDecimal lastPrice, Instant lastPriceAt,
                               BigDecimal marketValue, BigDecimal unrealizedPnl,
                               BigDecimal unrealizedPnlPercent, BigDecimal realizedPnl,
                               String currency) {
        static PositionView from(Position p) {
            Money value = p.marketValue();
            Money unreal = p.unrealizedPnl();
            BigDecimal pct = (unreal == null || p.costBasisMoney().isZero())
                    ? null
                    : unreal.amount().divide(p.costBasisMoney().amount(), 6,
                            java.math.RoundingMode.HALF_EVEN).multiply(BigDecimal.valueOf(100));
            return new PositionView(
                    p.getTicker(), p.getQuantity(), p.getCostBasis(),
                    CostBasis.unitCost(p.getQuantity(), p.costBasisMoney()).amount(),
                    p.getLastPrice(), p.getLastPriceAt(),
                    value == null ? null : value.amount(),
                    unreal == null ? null : unreal.amount(),
                    pct, p.getRealizedPnl(), p.getCurrency());
        }
    }

    // Portfolios

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Portfolio create(@RequestBody CreatePortfolioRequest req) {
        return portfolios.save(new Portfolio(req.owner(), req.name(),
                req.baseCurrency() == null ? Money.MAD : req.baseCurrency()));
    }

    @GetMapping
    public List<Portfolio> list(@RequestParam(required = false) String owner) {
        return owner == null ? portfolios.findAll() : portfolios.findByOwner(owner);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Portfolio> get(@PathVariable UUID id) {
        return portfolios.findById(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // Positions and trades

    @GetMapping("/{id}/positions")
    public List<PositionView> positions(@PathVariable UUID id) {
        return positions.findOpenPositions(id).stream().map(PositionView::from).toList();
    }

    @GetMapping("/{id}/transactions")
    public List<TradeTransaction> transactions(@PathVariable UUID id) {
        return transactions.findByPortfolioIdOrderByExecutedAtDescIdDesc(id);
    }

    @PostMapping("/{id}/buy")
    @ResponseStatus(HttpStatus.CREATED)
    public TradeTransaction buy(@PathVariable UUID id, @RequestBody TradeRequest req) {
        return trades.recordBuy(id, req.ticker().toUpperCase(), req.quantity(),
                Money.mad(req.price()),
                Money.mad(req.fees() == null ? BigDecimal.ZERO : req.fees()),
                req.executedAt() == null ? Instant.now() : req.executedAt(), req.note());
    }

    @PostMapping("/{id}/sell")
    @ResponseStatus(HttpStatus.CREATED)
    public TradeTransaction sell(@PathVariable UUID id, @RequestBody TradeRequest req) {
        return trades.recordSell(id, req.ticker().toUpperCase(), req.quantity(),
                Money.mad(req.price()),
                Money.mad(req.fees() == null ? BigDecimal.ZERO : req.fees()),
                req.executedAt() == null ? Instant.now() : req.executedAt(), req.note());
    }

    /** Rebuilds a position from the ledger. Used to check the stored one. */
    @PostMapping("/{id}/positions/{ticker}/rebuild")
    public PositionView rebuild(@PathVariable UUID id, @PathVariable String ticker) {
        return PositionView.from(trades.rebuild(id, ticker.toUpperCase()));
    }

    // Risk

    /**
     * Portfolio summary, optionally in another currency (?currency=USD).
     * The response includes the rate used, its date, and if it's stale.
     */
    @GetMapping("/{id}/summary")
    public RiskService.PortfolioRisk summary(@PathVariable UUID id,
                                             @RequestParam(defaultValue = "MAD") String currency) {
        String target = currency.trim().toUpperCase();
        if (!target.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO code, got '" + currency + "'");
        }
        RiskService.PortfolioRisk inMad = risk.computePortfolioRisk(id);
        return Money.MAD.equals(target) ? inMad : inMad.inCurrency(fx.latest(Money.MAD, target));
    }

    @GetMapping("/instruments/{ticker}/risk")
    public RiskService.InstrumentRisk instrumentRisk(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "6M") String range,
            @RequestParam(required = false) String benchmark) {
        return risk.computeInstrumentRisk(ticker.toUpperCase(), range, benchmark);
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("costBasisMethod", trades.method().name());
    }

    /** Selling more than you own is a 400, not a 500. Retrying won't fix it. */
    @ExceptionHandler(CostBasis.InsufficientSharesException.class)
    public ResponseEntity<Map<String, String>> handleOversell(CostBasis.InsufficientSharesException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INSUFFICIENT_SHARES", "message", e.getMessage()));
    }

    @ExceptionHandler(FxRateClient.FxUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleFx(FxRateClient.FxUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "FX_RATE_UNAVAILABLE", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_REQUEST", "message", e.getMessage()));
    }

    @ExceptionHandler(Money.CurrencyMismatchException.class)
    public ResponseEntity<Map<String, String>> handleCurrency(Money.CurrencyMismatchException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "CURRENCY_MISMATCH", "message", e.getMessage()));
    }
}
