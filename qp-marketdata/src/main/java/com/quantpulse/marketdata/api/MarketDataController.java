package com.quantpulse.marketdata.api;

import com.quantpulse.marketdata.domain.Instrument;
import com.quantpulse.marketdata.domain.MarketIndex;
import com.quantpulse.marketdata.domain.OhlcvBar;
import com.quantpulse.marketdata.ingestion.HistoryBackfillService;
import com.quantpulse.marketdata.ingestion.MarketIngestionService;
import com.quantpulse.marketdata.ingestion.TradingCalendar;
import com.quantpulse.marketdata.quota.QuotaGovernor;
import com.quantpulse.marketdata.repository.InstrumentRepository;
import com.quantpulse.marketdata.repository.OhlcvBarRepository;
import com.quantpulse.marketdata.repository.DividendRepository;
import com.quantpulse.marketdata.repository.IndexHistoryRepository;
import com.quantpulse.marketdata.repository.MarketIndexRepository;
import com.quantpulse.marketdata.repository.SectorRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Read API for market data, plus a few ops endpoints.
 *
 * Everything is read from our database, never from the upstream API, so refreshing
 * the dashboard costs no API calls.
 */
@RestController
@RequestMapping("/api/v1")
public class MarketDataController {

    private final InstrumentRepository instruments;
    private final OhlcvBarRepository bars;
    private final MarketIndexRepository indices;
    private final SectorRepository sectors;
    private final IndexHistoryRepository indexHistory;
    private final DividendRepository dividends;
    private final QuotaGovernor quota;
    private final TradingCalendar calendar;
    private final MarketIngestionService ingestion;
    private final HistoryBackfillService backfill;
    private final com.quantpulse.marketdata.ingestion.Week52RangeService week52;
    private final com.quantpulse.marketdata.upstream.MarketDataProvider provider;

    public MarketDataController(InstrumentRepository instruments,
                                OhlcvBarRepository bars,
                                MarketIndexRepository indices,
                                SectorRepository sectors,
                                IndexHistoryRepository indexHistory,
                                DividendRepository dividends,
                                QuotaGovernor quota,
                                TradingCalendar calendar,
                                MarketIngestionService ingestion,
                                HistoryBackfillService backfill,
                                com.quantpulse.marketdata.ingestion.Week52RangeService week52,
                                com.quantpulse.marketdata.upstream.MarketDataProvider provider) {
        this.instruments = instruments;
        this.bars = bars;
        this.indices = indices;
        this.sectors = sectors;
        this.indexHistory = indexHistory;
        this.dividends = dividends;
        this.quota = quota;
        this.calendar = calendar;
        this.ingestion = ingestion;
        this.backfill = backfill;
        this.week52 = week52;
        this.provider = provider;
    }

    @GetMapping("/instruments")
    public List<InstrumentView> listInstruments(@RequestParam(required = false) String sector) {
        List<Instrument> rows = sector == null
                ? instruments.findByActiveTrueOrderByMarketCapDesc()
                : instruments.findBySectorCodeAndActiveTrue(sector);
        return rows.stream().map(InstrumentView::from).toList();
    }

    @GetMapping("/instruments/{ticker}")
    public ResponseEntity<InstrumentView> getInstrument(@PathVariable String ticker) {
        return instruments.findByTicker(ticker.toUpperCase())
                .map(InstrumentView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/instruments/{ticker}/history")
    public List<BarView> history(@PathVariable String ticker,
                                 @RequestParam(defaultValue = "1Y") String range) {
        LocalDate to = LocalDate.now();
        LocalDate from = switch (range.toUpperCase()) {
            case "1M" -> to.minusMonths(1);
            case "3M" -> to.minusMonths(3);
            case "6M" -> to.minusMonths(6);
            case "MAX" -> to.minusYears(20);
            default -> to.minusYears(1);
        };
        return bars.findSeries(ticker.toUpperCase(), from, to).stream().map(BarView::from).toList();
    }

    @GetMapping("/search")
    public List<InstrumentView> search(@RequestParam String q) {
        return instruments.search(q).stream().limit(25).map(InstrumentView::from).toList();
    }

    @GetMapping("/indices")
    public List<MarketIndex> listIndices() {
        return indices.findAll();
    }

    /**
     * Index history in the same format as instrument history, so beta can be computed the same way.
     * An index has no OHLC, so the close is used for all four.
     */
    @GetMapping("/indices/{code}/history")
    public List<BarView> indexHistory(@PathVariable String code,
                                      @RequestParam(defaultValue = "1Y") String range) {
        LocalDate to = LocalDate.now();
        LocalDate from = switch (range.toUpperCase()) {
            case "1M" -> to.minusMonths(1);
            case "3M" -> to.minusMonths(3);
            case "6M" -> to.minusMonths(6);
            case "MAX" -> to.minusYears(20);
            default -> to.minusYears(1);
        };
        return indexHistory.findSeries(code.toUpperCase(), from, to).stream()
                .map(h -> new BarView(h.getSessionDate(), h.getValue(), h.getValue(),
                        h.getValue(), h.getValue(), 0L))
                .toList();
    }

    /** Dividends for one instrument, newest first. */
    @GetMapping("/instruments/{ticker}/dividends")
    public List<DividendView> dividends(@PathVariable String ticker) {
        return dividends.findByTickerOrderByExDateDesc(ticker.toUpperCase()).stream()
                .map(d -> new DividendView(d.getExDate(), d.getPaymentDate(),
                        d.getAmount(), d.getCurrency()))
                .toList();
    }

    @GetMapping("/sectors")
    public List<Map<String, Object>> listSectors() {
        return sectors.findAll().stream()
                .map(s -> Map.<String, Object>of(
                        "code", s.getCode(), "name", s.getName(), "count", s.getInstrumentCount()))
                .toList();
    }

    /** Market summary computed from our data. */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        List<Instrument> all = instruments.findByActiveTrueOrderByMarketCapDesc();
        BigDecimal totalCap = all.stream()
                .map(Instrument::getMarketCap)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of(
                "instrumentCount", all.size(),
                "totalMarketCap", totalCap,
                "marketOpen", calendar.isTradingNow(),
                "session", calendar.sessionDescription(),
                "localTime", calendar.nowInCasablanca().toString(),
                // DRAHMI when live, FIXTURE/SIMULATED in dev. The AI brief needs to know if prices are real.
                "priceSource", provider.sourceName());
    }

    // Ops

    /** Today's API quota. */
    @GetMapping("/ops/quota")
    public QuotaGovernor.QuotaSnapshot quota() {
        return quota.snapshot();
    }

    /** Run ingestion now (for demos and testing). */
    @PostMapping("/ops/ingest")
    public Map<String, Object> triggerIngest() {
        var result = ingestion.ingestMarketSnapshot();
        int indicesChanged = ingestion.ingestIndices();
        int caps = ingestion.harvestMarketCaps();
        return Map.of("seen", result.seen(), "created", result.created(),
                "priceChanges", result.changed(), "indicesChanged", indicesChanged,
                "marketCapsHarvested", caps);
    }

    @PostMapping("/ops/backfill/seed")
    public Map<String, Object> seedBackfill(@RequestParam(defaultValue = "1Y") String range) {
        return Map.of("created", backfill.seedTasks(range), "pending", backfill.pendingCount());
    }

    @PostMapping("/ops/backfill/run")
    public Map<String, Object> runBackfill() {
        int completed = backfill.runNextBatch();
        return Map.of("completed", completed, "pending", backfill.pendingCount());
    }

    @PostMapping("/ops/backfill/dividends/seed")
    public Map<String, Object> seedDividendBackfill() {
        return Map.of("created", backfill.seedDividendTasks(),
                "pending", backfill.pendingCount(
                        com.quantpulse.marketdata.domain.BackfillTask.Kind.DIVIDEND));
    }

    @PostMapping("/ops/backfill/dividends/run")
    public Map<String, Object> runDividendBackfill() {
        int completed = backfill.runNextBatch(
                com.quantpulse.marketdata.domain.BackfillTask.Kind.DIVIDEND);
        return Map.of("completed", completed,
                "pending", backfill.pendingCount(
                        com.quantpulse.marketdata.domain.BackfillTask.Kind.DIVIDEND));
    }

    @PostMapping("/ops/backfill/fundamentals/seed")
    public Map<String, Object> seedFundamentalsBackfill() {
        return Map.of("created", backfill.seedFundamentalsTasks());
    }

    @PostMapping("/ops/backfill/fundamentals/run")
    public Map<String, Object> runFundamentalsBackfill() {
        int completed = backfill.runNextBatch(
                com.quantpulse.marketdata.domain.BackfillTask.Kind.FUNDAMENTALS);
        return Map.of("completed", completed,
                "pending", backfill.pendingCount(
                        com.quantpulse.marketdata.domain.BackfillTask.Kind.FUNDAMENTALS));
    }

    /** Recompute 52-week high/low from stored bars. No API calls. */
    @PostMapping("/ops/week52/refresh")
    public Map<String, Object> refreshWeek52() {
        return Map.of("updated", week52.refresh());
    }

    @PostMapping("/ops/backfill/index")
    public Map<String, Object> backfillIndex(@RequestParam String code,
                                             @RequestParam(defaultValue = "1Y") String range) {
        return Map.of("written", backfill.backfillIndex(code.toUpperCase(), range));
    }

    // Response types. We don't return entities so the DB schema isn't exposed in the API.

    public record InstrumentView(String ticker, String name, String isin, String sector,
                                 String currency, BigDecimal price, java.time.Instant priceAt,
                                 BigDecimal marketCap, BigDecimal dividendYield,
                                 BigDecimal peRatio, BigDecimal week52High, BigDecimal week52Low,
                                 BigDecimal changePercent) {
        static InstrumentView from(Instrument i) {
            return new InstrumentView(i.getTicker(), i.getName(), i.getIsin(), i.getSectorCode(),
                    i.getCurrency(), i.getLastPrice(), i.getLastPriceAt(), i.getMarketCap(),
                    i.getDividendYield(), i.getPeRatio(), i.getWeek52High(), i.getWeek52Low(),
                    i.getDayChangePercent());
        }
    }

    public record DividendView(LocalDate exDate, LocalDate paymentDate,
                               BigDecimal amount, String currency) {
    }

    public record BarView(LocalDate date, BigDecimal open, BigDecimal high,
                          BigDecimal low, BigDecimal close, long volume) {
        static BarView from(OhlcvBar b) {
            return new BarView(b.getSessionDate(), b.getOpen(), b.getHigh(),
                    b.getLow(), b.getClose(), b.getVolume());
        }
    }
}
