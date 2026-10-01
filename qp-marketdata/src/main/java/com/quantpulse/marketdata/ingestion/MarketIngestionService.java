package com.quantpulse.marketdata.ingestion;

import com.quantpulse.common.event.IndexTickEvent;
import com.quantpulse.common.event.PriceTickEvent;
import com.quantpulse.common.event.Topology;
import com.quantpulse.marketdata.domain.Instrument;
import com.quantpulse.marketdata.domain.MarketIndex;
import com.quantpulse.marketdata.domain.Sector;
import com.quantpulse.marketdata.outbox.OutboxWriter;
import com.quantpulse.marketdata.repository.InstrumentRepository;
import com.quantpulse.marketdata.repository.MarketIndexRepository;
import com.quantpulse.marketdata.repository.SectorRepository;
import com.quantpulse.marketdata.upstream.DrahmiDtos;
import com.quantpulse.marketdata.upstream.MarketDataProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns one API snapshot into saved prices and events.
 *
 * All in one transaction, so prices and outbox events are saved together or not at all.
 */
@Service
public class MarketIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MarketIngestionService.class);

    private final MarketDataProvider provider;
    private final InstrumentRepository instruments;
    private final SectorRepository sectors;
    private final MarketIndexRepository indices;
    private final OutboxWriter outbox;
    private final Counter pricesIngested;
    private final Counter pricesChanged;

    public MarketIngestionService(MarketDataProvider provider,
                                  InstrumentRepository instruments,
                                  SectorRepository sectors,
                                  MarketIndexRepository indices,
                                  OutboxWriter outbox,
                                  MeterRegistry meterRegistry) {
        this.provider = provider;
        this.instruments = instruments;
        this.sectors = sectors;
        this.indices = indices;
        this.outbox = outbox;
        this.pricesIngested = Counter.builder("quantpulse.ingestion.prices")
                .description("Instrument prices seen in a snapshot").register(meterRegistry);
        this.pricesChanged = Counter.builder("quantpulse.ingestion.price.changes")
                .description("Prices that actually moved and produced an event").register(meterRegistry);
    }

    /**
     * Ingests the whole market in one call.
     *
     * One call for all 81 instruments is what makes the quota work. One call per
     * instrument would be 81 calls per refresh.
     */
    @Transactional
    public IngestionResult ingestMarketSnapshot() {
        List<DrahmiDtos.StockSummary> snapshot = provider.fetchMarketSnapshot();
        if (snapshot.isEmpty()) {
            log.debug("[INGEST] empty snapshot — upstream unavailable or quota denied");
            return IngestionResult.empty();
        }

        // instrument.sector_code references sector.code, so sectors must exist first.
        // Loading them here lets an empty database work on the first run.
        ensureSectorsLoaded();
        Set<String> knownSectors = sectors.findAll().stream()
                .map(Sector::getCode)
                .collect(Collectors.toSet());

        // Load all once and look up in memory instead of 81 queries.
        Map<String, Instrument> existing = new HashMap<>();
        instruments.findAllForSnapshot().forEach(i -> existing.put(i.getTicker(), i));

        int created = 0;
        int changed = 0;

        for (DrahmiDtos.StockSummary row : snapshot) {
            if (row.ticker() == null || row.ticker().isBlank()) {
                continue;
            }
            Instrument instrument = existing.get(row.ticker());
            if (instrument == null) {
                instrument = new Instrument(row.ticker(), row.name() == null ? row.ticker() : row.name());
                created++;
            } else if (row.name() != null) {
                instrument.setName(row.name());
            }

            // An unknown sector shouldn't fail the whole snapshot. Store null and log it,
            // the nightly sector refresh will fix it.
            if (row.sector() != null) {
                if (knownSectors.contains(row.sector())) {
                    instrument.setSectorCode(row.sector());
                } else {
                    log.warn("[INGEST] unknown sector '{}' for {} — storing null until the next "
                            + "sector refresh", row.sector(), row.ticker());
                    instrument.setSectorCode(null);
                }
            }
            if (row.currency() != null) {
                instrument.setCurrency(row.currency());
            }

            Instant asOf = row.updatedAt() != null ? row.updatedAt() : Instant.now();
            boolean priceMoved = instrument.applyPrice(row.price(), row.change(), asOf);
            instruments.save(instrument);
            pricesIngested.increment();

            if (priceMoved) {
                changed++;
                pricesChanged.increment();
                // Only publish when the price changed.
                PriceTickEvent event = PriceTickEvent.of(
                        instrument.getTicker(), row.price(), instrument.getCurrency(),
                        instrument.getSectorCode(), provider.sourceName(), asOf);
                outbox.append("Instrument", instrument.getTicker(),
                        Topology.routingKey(Topology.RK_PRICE_TICK, instrument.getTicker()), event);
            }
        }

        log.info("[INGEST] snapshot: {} instruments, {} new, {} price changes (source={})",
                snapshot.size(), created, changed, provider.sourceName());
        return new IngestionResult(snapshot.size(), created, changed);
    }

    @Transactional
    public int ingestIndices() {
        List<DrahmiDtos.IndexDto> rows = provider.fetchIndices();
        int changed = 0;

        for (DrahmiDtos.IndexDto row : rows) {
            MarketIndex index = indices.findById(row.code())
                    .orElseGet(() -> new MarketIndex(row.code(),
                            row.name() == null ? row.code() : row.name()));
            if (row.name() != null) {
                index.setName(row.name());
            }
            Instant asOf = row.updatedAt() != null ? row.updatedAt() : Instant.now();
            boolean moved = index.apply(row.value(), row.changePercent(), row.changeValue(), asOf);
            indices.save(index);

            if (moved) {
                changed++;
                outbox.append("MarketIndex", index.getCode(),
                        Topology.routingKey(Topology.RK_INDEX_TICK, index.getCode()),
                        IndexTickEvent.of(index.getCode(), index.getName(), row.value(),
                                row.changePercent(), row.changeValue(), asOf));
            }
        }
        if (!rows.isEmpty()) {
            log.info("[INGEST] indices: {} seen, {} moved", rows.size(), changed);
        }
        return changed;
    }

    /**
     * Gets market cap from the gainers / losers / most-active lists.
     *
     * /stocks doesn't include marketCap and fetching each stock would be 81 calls.
     * These 3 calls cover most liquid stocks. The others keep a null cap.
     */
    @Transactional
    public int harvestMarketCaps() {
        List<DrahmiDtos.StockSummary> movers = provider.fetchMovers();
        if (movers.isEmpty()) {
            return 0;
        }
        Map<String, Instrument> byTicker = new HashMap<>();
        instruments.findAllForSnapshot().forEach(i -> byTicker.put(i.getTicker(), i));

        int updated = 0;
        for (DrahmiDtos.StockSummary row : movers) {
            Instrument instrument = byTicker.get(row.ticker());
            if (instrument != null && row.marketCap() != null) {
                instrument.applyFundamentals(row.marketCap(), null, null, null, null, null);
                instruments.save(instrument);
                updated++;
            }
        }
        log.info("[INGEST] harvested market cap for {} instruments from {} mover rows",
                updated, movers.size());
        return updated;
    }

    /**
     * Loads sectors if the table is empty. One call the first time, nothing after,
     * so it's safe to call every cycle.
     */
    private void ensureSectorsLoaded() {
        if (sectors.count() == 0) {
            log.info("[INGEST] sector table empty — bootstrapping before instrument upsert");
            ingestSectors();
        }
    }

    /** Sectors rarely change, so once a day is enough. */
    @Transactional
    public int ingestSectors() {
        List<DrahmiDtos.SectorDto> rows = provider.fetchSectors();
        for (DrahmiDtos.SectorDto row : rows) {
            if (row.code() == null) {
                continue;
            }
            int count = row.count() == null ? 0 : row.count();
            sectors.findByCode(row.code())
                    .ifPresentOrElse(
                            s -> s.update(row.name(), count),
                            () -> sectors.save(new Sector(row.code(), row.name(), count)));
        }
        if (!rows.isEmpty()) {
            log.info("[INGEST] sectors: {}", rows.size());
        }
        return rows.size();
    }

    public record IngestionResult(int seen, int created, int changed) {
        static IngestionResult empty() {
            return new IngestionResult(0, 0, 0);
        }
    }
}
