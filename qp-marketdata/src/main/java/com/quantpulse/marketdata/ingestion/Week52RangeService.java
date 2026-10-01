package com.quantpulse.marketdata.ingestion;

import com.quantpulse.marketdata.domain.Instrument;
import com.quantpulse.marketdata.repository.InstrumentRepository;
import com.quantpulse.marketdata.repository.OhlcvBarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Computes each stock's 52-week high/low from the bars we already have.
 *
 * The API only gives it per stock (one call each) and sometimes says 0 for the low.
 * We already store a year of bars, so it's one query and no API calls.
 * Bars with a 0 low are skipped as bad data.
 */
@Service
public class Week52RangeService {

    private static final Logger log = LoggerFactory.getLogger(Week52RangeService.class);

    private final OhlcvBarRepository bars;
    private final InstrumentRepository instruments;
    private final Clock clock;

    public Week52RangeService(OhlcvBarRepository bars, InstrumentRepository instruments, Clock clock) {
        this.bars = bars;
        this.instruments = instruments;
        this.clock = clock;
    }

    /** @return number of instruments updated */
    @Transactional
    public int refresh() {
        Map<String, OhlcvBarRepository.PriceRange> ranges = bars
                .findRangesSince(LocalDate.now(clock).minusWeeks(52)).stream()
                .collect(Collectors.toMap(OhlcvBarRepository.PriceRange::getTicker, Function.identity()));
        int updated = 0;
        for (Instrument instrument : instruments.findAll()) {
            var range = ranges.get(instrument.getTicker());
            if (range != null) {
                instrument.applyWeek52Range(range.getHigh(), range.getLow());
                updated++;
            }
        }
        log.info("[52W] ranges refreshed for {} instruments from stored bars", updated);
        return updated;
    }
}
