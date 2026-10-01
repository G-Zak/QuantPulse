package com.quantpulse.common.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * New price for one instrument.
 *
 * Sent by qp-marketdata only when the price changed. Otherwise we'd send 81 events
 * every 15 minutes for nothing.
 *
 * price is a BigDecimal. Consumers need USE_BIG_DECIMAL_FOR_FLOATS or Jackson
 * will read it back as a double.
 */
public record PriceTickEvent(
        String eventId,
        Instant occurredAt,
        String ticker,
        BigDecimal price,
        String currency,
        String sector,
        /** LIVE, FIXTURE or SIMULATED, so the UI can flag non-live data. */
        String source,
        /** Upstream's timestamp, about 15 min behind occurredAt. */
        Instant asOf
) implements DomainEvent {

    public static final String TYPE = "price.tick";

    @JsonCreator
    public PriceTickEvent {
    }

    public static PriceTickEvent of(String ticker, BigDecimal price, String currency,
                                    String sector, String source, Instant asOf) {
        return new PriceTickEvent(
                UUID.randomUUID().toString(), Instant.now(),
                ticker, price, currency, sector, source, asOf);
    }

    @Override
    @JsonProperty("eventType")
    public String eventType() {
        return TYPE;
    }
}
