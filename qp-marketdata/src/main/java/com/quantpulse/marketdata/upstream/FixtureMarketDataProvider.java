package com.quantpulse.marketdata.upstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replays the 36 responses saved in ops/fixtures/, so dev and tests cost no API calls.
 * Active in every profile except live.
 *
 * - Reference data (sectors, indices, stock list, the 3 instruments with recorded
 *   history) comes straight from the files, with the API's quirks (beta null,
 *   accented sector codes, change 0).
 * - Prices move a little on each call (GBM), and history is generated for the 78
 *   instruments we didn't record. Otherwise nothing would ever change and no events,
 *   alerts or valuation updates would happen.
 *
 * Generated series are seeded from the ticker, so they're the same on every run
 * and tests on volatility stay stable.
 */
@Component
@Profile("!live")
public class FixtureMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(FixtureMarketDataProvider.class);

    /** Instruments with real recorded history. The rest is generated. */
    private static final List<String> RECORDED_HISTORY = List.of("ATW", "IAM", "MNG");

    private final ObjectMapper mapper;
    private final ResourceLoader resourceLoader;
    private final String fixtureLocation;
    private final boolean simulateDrift;

    /** Current moving price per ticker, starting from the recorded snapshot. */
    private final Map<String, BigDecimal> drifted = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> driftedIndices = new ConcurrentHashMap<>();
    private final Random random = new Random();

    private List<DrahmiDtos.StockSummary> baseSnapshot = List.of();

    public FixtureMarketDataProvider(ObjectMapper mapper,
                                     ResourceLoader resourceLoader,
                                     @Value("${quantpulse.fixtures.location:file:../ops/fixtures/}") String fixtureLocation,
                                     @Value("${quantpulse.fixtures.simulate-drift:true}") boolean simulateDrift) {
        this.mapper = mapper;
        this.resourceLoader = resourceLoader;
        this.fixtureLocation = fixtureLocation.endsWith("/") ? fixtureLocation : fixtureLocation + "/";
        this.simulateDrift = simulateDrift;
    }

    @PostConstruct
    void loadBaseSnapshot() {
        this.baseSnapshot = readOptional("stocks-all", DrahmiDtos.StockPage.class)
                .map(DrahmiDtos.StockPage::items)
                .orElseGet(List::of);
        baseSnapshot.forEach(s -> {
            if (s.price() != null) {
                drifted.put(s.ticker(), s.price());
            }
        });
        log.info("[FIXTURES] loaded {} instruments from {} (drift={})",
                baseSnapshot.size(), fixtureLocation, simulateDrift);
        if (baseSnapshot.isEmpty()) {
            log.warn("[FIXTURES] no instruments loaded — check quantpulse.fixtures.location={}",
                    fixtureLocation);
        }
    }

    @Override
    public String sourceName() {
        return simulateDrift ? "SIMULATED" : "FIXTURE";
    }

    @Override
    public List<DrahmiDtos.StockSummary> fetchMarketSnapshot() {
        if (!simulateDrift) {
            return baseSnapshot;
        }
        List<DrahmiDtos.StockSummary> out = new ArrayList<>(baseSnapshot.size());
        for (DrahmiDtos.StockSummary s : baseSnapshot) {
            BigDecimal next = nextPrice(s.ticker(), s.price());
            BigDecimal change = s.price() == null || s.price().signum() == 0
                    ? BigDecimal.ZERO
                    : next.subtract(s.price())
                        .divide(s.price(), 6, RoundingMode.HALF_EVEN)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_EVEN);
            out.add(new DrahmiDtos.StockSummary(
                    s.ticker(), s.name(), next, change, s.sector(), s.currency(),
                    s.marketCap(), Instant.now()));
        }
        return out;
    }

    @Override
    public List<DrahmiDtos.IndexDto> fetchIndices() {
        List<DrahmiDtos.IndexDto> base = readList("indices", DrahmiDtos.IndexDto.class);
        if (!simulateDrift) {
            return base;
        }
        List<DrahmiDtos.IndexDto> out = new ArrayList<>(base.size());
        for (DrahmiDtos.IndexDto i : base) {
            BigDecimal prev = driftedIndices.getOrDefault(i.code(), i.value());
            BigDecimal next = gbmStep(prev, 0.00002, 0.0008);
            driftedIndices.put(i.code(), next);
            BigDecimal changeValue = next.subtract(i.value());
            BigDecimal changePercent = i.value().signum() == 0 ? BigDecimal.ZERO
                    : changeValue.divide(i.value(), 6, RoundingMode.HALF_EVEN)
                        .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_EVEN);
            out.add(new DrahmiDtos.IndexDto(i.code(), i.name(), next, changePercent,
                    changeValue.setScale(2, RoundingMode.HALF_EVEN), Instant.now()));
        }
        return out;
    }

    @Override
    public List<DrahmiDtos.StockSummary> fetchMovers() {
        List<DrahmiDtos.StockSummary> merged = new ArrayList<>();
        for (String slug : List.of("market-gainers", "market-losers", "market-most-active")) {
            readOptional(slug, DrahmiDtos.StockPage.class)
                    .map(DrahmiDtos.StockPage::items)
                    .ifPresent(merged::addAll);
        }
        return merged;
    }

    /**
     * Dividend history. Real for ATW (16 years recorded), generated for the others.
     *
     * The generated data tries to look like Casablanca: one dividend a year paid mid-year,
     * about 6% growth, and sometimes (about 1 year in 12) a cut or a skipped year.
     * About 20% of instruments pay nothing.
     */
    @Override
    public List<DrahmiDtos.DividendDto> fetchDividends(String ticker) {
        String upper = ticker.toUpperCase();
        List<DrahmiDtos.DividendDto> recorded = readList("dividends-" + upper,
                DrahmiDtos.DividendDto.class);
        if (!recorded.isEmpty()) {
            return recorded;
        }

        Random rng = new Random(upper.hashCode() * 31L + 7);
        // ~20% of instruments don't pay.
        if (rng.nextInt(10) < 2) {
            return List.of();
        }

        BigDecimal price = drifted.getOrDefault(upper, BigDecimal.valueOf(100));
        // Starting yield between about 1.5% and 6%.
        double startingYield = 0.015 + 0.045 * rng.nextDouble();
        BigDecimal dps = price.multiply(BigDecimal.valueOf(startingYield), MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_EVEN);

        int years = 8 + rng.nextInt(9);
        List<DrahmiDtos.DividendDto> out = new ArrayList<>();
        int currentYear = LocalDate.now().getYear();

        // Built from newest to oldest so the latest one matches today's price.
        for (int i = 0; i < years; i++) {
            int year = currentYear - i;
            int roll = rng.nextInt(12);
            if (roll == 0 && i > 0) {
                continue;  // skipped year
            }
            LocalDate ex = LocalDate.of(year, 5 + rng.nextInt(3), 1 + rng.nextInt(27));
            out.add(new DrahmiDtos.DividendDto(
                    upper + "-" + year, ex, ex.plusDays(7 + rng.nextInt(10)),
                    dps, "MAD"));

            // Going backwards, so remove one year of growth. Sometimes undo a cut instead.
            double factor = roll == 1 ? 0.75 : 1.06;
            dps = dps.divide(BigDecimal.valueOf(factor), 6, RoundingMode.HALF_EVEN)
                    .setScale(2, RoundingMode.HALF_EVEN);
            if (dps.signum() <= 0) {
                break;
            }
        }
        return out;
    }

    @Override
    public List<DrahmiDtos.SectorDto> fetchSectors() {
        return readList("sectors", DrahmiDtos.SectorDto.class);
    }

    @Override
    public Optional<DrahmiDtos.StockDetail> fetchStockDetail(String ticker) {
        Optional<DrahmiDtos.StockDetail> recorded =
                readOptional("stock-" + ticker, DrahmiDtos.StockDetail.class);
        if (recorded.isPresent()) {
            return recorded;
        }
        // Generate a detail record for instruments we didn't record.
        //
        // P/E and yield come from the instrument's generated dividends, so payout = yield x PE
        // stays between 25% and 85%. Otherwise the dividend analysis would show nonsense.
        return baseSnapshot.stream()
                .filter(s -> s.ticker().equalsIgnoreCase(ticker))
                .findFirst()
                .map(s -> {
                    BigDecimal price = drifted.getOrDefault(s.ticker(), s.price());
                    Random rng = new Random(s.ticker().hashCode() * 17L + 3);

                    BigDecimal ttm = trailingDividendPerShare(s.ticker());
                    BigDecimal yield = null;
                    BigDecimal pe = null;
                    if (price != null && price.signum() > 0) {
                        if (ttm.signum() > 0) {
                            yield = ttm.divide(price, 6, RoundingMode.HALF_EVEN)
                                    .multiply(BigDecimal.valueOf(100))
                                    .setScale(2, RoundingMode.HALF_EVEN);
                            // Pick a payout between 25% and 85%, then PE = payout / yield.
                            double payout = 0.25 + 0.60 * rng.nextDouble();
                            double yieldFraction = ttm.doubleValue() / price.doubleValue();
                            pe = BigDecimal.valueOf(payout / yieldFraction)
                                    .setScale(2, RoundingMode.HALF_EVEN);
                        } else {
                            // Pays no dividend but still has earnings.
                            pe = BigDecimal.valueOf(8 + 22 * rng.nextDouble())
                                    .setScale(2, RoundingMode.HALF_EVEN);
                        }
                    }
                    return new DrahmiDtos.StockDetail(
                            s.ticker(), s.name(), null, s.sector(), "Bourse de Casablanca",
                            s.currency(), price, s.change(), s.marketCap(),
                            yield, pe, null, null, null, null, Instant.now());
                });
    }

    @Override
    public List<DrahmiDtos.OhlcvPoint> fetchHistory(String ticker, String range) {
        if (RECORDED_HISTORY.contains(ticker.toUpperCase())) {
            Optional<DrahmiDtos.HistoryResponse> recorded =
                    readOptional("stock-" + ticker.toUpperCase() + "-history-1Y",
                            DrahmiDtos.HistoryResponse.class);
            if (recorded.isPresent()) {
                return trimToRange(recorded.get().points(), range);
            }
        }
        return synthesiseHistory(ticker, sessionsFor(range));
    }

    @Override
    public List<DrahmiDtos.IndexHistoryPoint> fetchIndexHistory(String code, String range) {
        List<DrahmiDtos.IndexHistoryPoint> points =
                readList("index-" + code.toUpperCase() + "-history-1Y", DrahmiDtos.IndexHistoryPoint.class);
        if (points.isEmpty()) {
            return List.of();
        }
        int keep = Math.min(points.size(), sessionsFor(range));
        return points.subList(points.size() - keep, points.size());
    }

    @Override
    public Optional<DrahmiDtos.MarketOverview> fetchOverview() {
        return readOptional("market-overview", DrahmiDtos.MarketOverview.class);
    }

    @Override
    public Optional<DrahmiDtos.MarketStatus> fetchStatus() {
        return readOptional("market-status", DrahmiDtos.MarketStatus.class);
    }

    @Override
    public Optional<DrahmiDtos.RiskMetrics> fetchRisk(String ticker, String range, String benchmark) {
        return readOptional("risk-" + ticker.toUpperCase() + "-" + range,
                new com.fasterxml.jackson.core.type.TypeReference<
                        DrahmiDtos.Envelope<DrahmiDtos.RiskMetrics>>() {})
                .map(DrahmiDtos.Envelope::data);
    }

    // Simulation

    private BigDecimal nextPrice(String ticker, BigDecimal seed) {
        BigDecimal prev = drifted.getOrDefault(ticker, seed == null ? BigDecimal.ONE : seed);
        BigDecimal next = gbmStep(prev, 0.00001, 0.0015);
        drifted.put(ticker, next);
        return next;
    }

    /**
     * One step of geometric Brownian motion:
     * S(t+dt) = S(t) * (1 + mu*dt + sigma*sqrt(dt)*Z), Z ~ N(0,1)
     *
     * GBM because prices can't go negative and move in percentages, not fixed amounts.
     */
    private BigDecimal gbmStep(BigDecimal price, double drift, double vol) {
        if (price == null || price.signum() <= 0) {
            return BigDecimal.ONE;
        }
        double shock = drift + vol * random.nextGaussian();
        BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(shock));
        BigDecimal next = price.multiply(factor, MathContext.DECIMAL64)
                .setScale(2, RoundingMode.HALF_EVEN);
        return next.signum() <= 0 ? price : next;
    }

    /**
     * Generates OHLCV history for instruments we didn't record.
     * Seeded from the ticker so it's the same every run.
     * About 10% of sessions have zero volume, like the real data (26 of ATW's 245).
     */
    private List<DrahmiDtos.OhlcvPoint> synthesiseHistory(String ticker, int sessions) {
        BigDecimal seed = drifted.getOrDefault(ticker.toUpperCase(), BigDecimal.valueOf(100));
        Random seeded = new Random(ticker.toUpperCase().hashCode());

        // Factor model.
        //
        // The first version generated each stock on its own, so they weren't correlated at all.
        // PCA found the first component explained only 4.8% of variance, a real market is
        // more like 30-60%. Beta and correlation were meaningless.
        //
        // Now returns are:
        //
        //     r_i(t) = beta_i * market(t) + gamma * sector(t) + idiosyncratic_i(t)
        //
        // All stocks share one market series, and stocks of the same sector share a sector series.
        double[] market = factorSeries(MARKET_FACTOR_SEED, sessions, 0.009);
        double[] sectorFactor = factorSeries(sectorSeedFor(ticker), sessions, 0.005);

        // Beta between about 0.5 and 1.6, fixed per ticker.
        double beta = 0.5 + 1.1 * ((Math.abs(ticker.toUpperCase().hashCode() % 1000)) / 1000.0);
        double idioVol = 0.006 + 0.008 * seeded.nextDouble();

        List<BigDecimal> closes = new ArrayList<>(sessions);
        BigDecimal price = seed;
        // Go backwards from today's price, then reverse, so the series ends at the current price.
        for (int i = sessions - 1; i >= 0; i--) {
            closes.add(price);
            double r = beta * market[i] + 0.6 * sectorFactor[i] + idioVol * seeded.nextGaussian();
            // Reverse step to go backwards.
            price = price.divide(BigDecimal.valueOf(Math.exp(r)), 6, RoundingMode.HALF_EVEN)
                    .setScale(2, RoundingMode.HALF_EVEN);
            if (price.signum() <= 0) {
                price = seed;
            }
        }
        java.util.Collections.reverse(closes);

        LocalDate date = LocalDate.now().minusDays(1);
        List<LocalDate> dates = new ArrayList<>(sessions);
        while (dates.size() < sessions) {
            // Weekdays only.
            if (date.getDayOfWeek().getValue() <= 5) {
                dates.add(date);
            }
            date = date.minusDays(1);
        }
        java.util.Collections.reverse(dates);

        List<DrahmiDtos.OhlcvPoint> points = new ArrayList<>(sessions);
        for (int i = 0; i < sessions; i++) {
            BigDecimal close = closes.get(i);
            BigDecimal open = i == 0 ? close : closes.get(i - 1);
            BigDecimal high = open.max(close).multiply(BigDecimal.valueOf(1.004), MathContext.DECIMAL64)
                    .setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal low = open.min(close).multiply(BigDecimal.valueOf(0.996), MathContext.DECIMAL64)
                    .setScale(2, RoundingMode.HALF_EVEN);
            // ~10% zero-volume sessions, like the real ATW data.
            long volume = seeded.nextInt(10) == 0 ? 0L
                    : 10_000L + (long) (seeded.nextDouble() * 2_000_000L);
            points.add(new DrahmiDtos.OhlcvPoint(dates.get(i), open, high, low, close,
                    BigDecimal.valueOf(volume)));
        }
        return points;
    }

    /** Dividends per share over the last 12 months, from the generated history. */
    private BigDecimal trailingDividendPerShare(String ticker) {
        LocalDate cutoff = LocalDate.now().minusDays(365);
        return fetchDividends(ticker).stream()
                .filter(d -> d.exDate() != null && !d.exDate().isBefore(cutoff))
                .map(DrahmiDtos.DividendDto::amount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Fixed seed so all instruments share the same market series. */
    private static final long MARKET_FACTOR_SEED = 20260803L;

    /** Common market returns, with some volatility clustering. */
    private double[] factorSeries(long seed, int length, double vol) {
        Random rng = new Random(seed);
        double[] out = new double[length];
        double currentVol = vol;
        for (int i = 0; i < length; i++) {
            // In real markets calm and volatile days come in groups.
            // Constant volatility would make risk look lower than it is.
            currentVol = 0.92 * currentVol + 0.08 * (vol * (0.5 + rng.nextDouble()));
            out[i] = currentVol * rng.nextGaussian();
        }
        return out;
    }

    /** Seed per sector, so stocks of a sector move together. */
    private long sectorSeedFor(String ticker) {
        return baseSnapshot.stream()
                .filter(s -> s.ticker().equalsIgnoreCase(ticker))
                .findFirst()
                .map(s -> s.sector() == null ? 0L : (long) s.sector().hashCode())
                .orElse(0L);
    }

    private List<DrahmiDtos.OhlcvPoint> trimToRange(List<DrahmiDtos.OhlcvPoint> points, String range) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        int keep = Math.min(points.size(), sessionsFor(range));
        return points.subList(points.size() - keep, points.size());
    }

    /** Approximate trading days per range. 245 per year in the real data, not 252. */
    private static int sessionsFor(String range) {
        return switch (range == null ? "1M" : range.toUpperCase()) {
            case "1D" -> 1;
            case "1W" -> 5;
            case "1M" -> 21;
            case "3M" -> 63;
            case "6M" -> 123;
            case "1Y" -> 245;
            default -> 245;
        };
    }

    // Fixture files

    private <T> Optional<T> readOptional(String slug, Class<T> type) {
        return read(slug, in -> mapper.readValue(in, type));
    }

    private <T> Optional<T> readOptional(String slug,
                                         com.fasterxml.jackson.core.type.TypeReference<T> type) {
        return read(slug, in -> mapper.readValue(in, type));
    }

    private <T> List<T> readList(String slug, Class<T> element) {
        CollectionType listType = mapper.getTypeFactory().constructCollectionType(List.class, element);
        return this.<List<T>>read(slug, in -> mapper.readValue(in, listType)).orElseGet(List::of);
    }

    private <T> Optional<T> read(String slug, FixtureReader<T> reader) {
        Resource resource = resourceLoader.getResource(fixtureLocation + slug + ".json");
        if (!resource.exists()) {
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            return Optional.ofNullable(reader.read(in));
        } catch (IOException e) {
            log.error("[FIXTURES] failed to read {}: {}", slug, e.getMessage());
            return Optional.empty();
        }
    }

    @FunctionalInterface
    private interface FixtureReader<T> {
        T read(InputStream in) throws IOException;
    }
}
