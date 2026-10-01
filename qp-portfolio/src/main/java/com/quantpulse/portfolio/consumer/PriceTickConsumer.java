package com.quantpulse.portfolio.consumer;

import com.quantpulse.common.event.PriceTickEvent;
import com.quantpulse.common.event.Topology;
import com.quantpulse.portfolio.domain.Position;
import com.quantpulse.portfolio.domain.ProcessedEvent;
import com.quantpulse.portfolio.repository.PositionRepository;
import com.quantpulse.portfolio.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Updates position values from the price stream.
 *
 * RabbitMQ delivers at least once, so we can get duplicates. Each event id is inserted
 * into processed_event; the primary key makes a second insert fail, and that's how we
 * know it was already handled. The insert and the position update are in the same
 * transaction.
 *
 * So delivery is at least once, but the effect happens once.
 *
 * Order isn't guaranteed either: a redelivered tick can come after a newer one.
 * Position.applyMarketPrice compares timestamps and ignores older prices.
 */
@Component
public class PriceTickConsumer {

    private static final Logger log = LoggerFactory.getLogger(PriceTickConsumer.class);
    private static final String CONSUMER_NAME = "portfolio-valuation";

    private final PositionRepository positions;
    private final ProcessedEventRepository processed;
    private final Counter applied;
    private final Counter duplicates;
    private final Counter stale;

    public PriceTickConsumer(PositionRepository positions,
                             ProcessedEventRepository processed,
                             MeterRegistry meterRegistry) {
        this.positions = positions;
        this.processed = processed;
        this.applied = Counter.builder("quantpulse.valuation.applied")
                .description("Price events that updated at least one position").register(meterRegistry);
        this.duplicates = Counter.builder("quantpulse.valuation.duplicates")
                .description("Price events rejected as already processed").register(meterRegistry);
        this.stale = Counter.builder("quantpulse.valuation.stale")
                .description("Price events ignored as older than the held price").register(meterRegistry);
    }

    @RabbitListener(queues = Topology.Q_PORTFOLIO_VALUATION, concurrency = "2-4")
    @Transactional
    public void onPriceTick(PriceTickEvent event,
                            @Header(name = "x-event-id", required = false) String headerEventId) {
        String rawId = headerEventId != null ? headerEventId : event.eventId();
        UUID eventId;
        try {
            eventId = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            // We can't dedupe a bad id. Throw so it goes through retry and ends in the parking lot.
            throw new IllegalArgumentException("unusable event id: " + rawId, e);
        }

        if (!claim(eventId)) {
            duplicates.increment();
            log.debug("[VALUATION] duplicate event {} — already applied", eventId);
            return;
        }

        List<Position> holdings = positions.findByTicker(event.ticker());
        if (holdings.isEmpty()) {
            return;  // no one holds it, the saved row still stops it being processed again
        }

        int updated = 0;
        for (Position position : holdings) {
            if (position.applyMarketPrice(event.price(), event.asOf())) {
                positions.save(position);
                updated++;
            } else {
                stale.increment();
            }
        }
        if (updated > 0) {
            applied.increment();
            log.debug("[VALUATION] {} @ {} -> {} position(s)", event.ticker(), event.price(), updated);
        }
    }

    /**
     * Marks the event as handled, returns false if it already was.
     * saveAndFlush sends the INSERT now, so a duplicate fails before we update positions.
     */
    private boolean claim(UUID eventId) {
        try {
            processed.saveAndFlush(new ProcessedEvent(eventId, CONSUMER_NAME));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
