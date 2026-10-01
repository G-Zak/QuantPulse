package com.quantpulse.common.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** New value for an index (MASI, MASI20). Used as the benchmark for beta/alpha. */
public record IndexTickEvent(
        String eventId,
        Instant occurredAt,
        String code,
        String name,
        BigDecimal value,
        BigDecimal changePercent,
        BigDecimal changeValue,
        Instant asOf
) implements DomainEvent {

    public static final String TYPE = "index.tick";

    @JsonCreator
    public IndexTickEvent {
    }

    public static IndexTickEvent of(String code, String name, BigDecimal value,
                                    BigDecimal changePercent, BigDecimal changeValue, Instant asOf) {
        return new IndexTickEvent(UUID.randomUUID().toString(), Instant.now(),
                code, name, value, changePercent, changeValue, asOf);
    }

    @Override
    @JsonProperty("eventType")
    public String eventType() {
        return TYPE;
    }
}
