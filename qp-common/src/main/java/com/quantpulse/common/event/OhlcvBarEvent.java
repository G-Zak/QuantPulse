package com.quantpulse.common.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One daily OHLCV bar, sent when the backfill stores a new session.
 *
 * volume can be 0 (ATW had 26 sessions with no trades last year),
 * so anything dividing by volume has to check for it.
 */
public record OhlcvBarEvent(
        String eventId,
        Instant occurredAt,
        String ticker,
        LocalDate sessionDate,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume
) implements DomainEvent {

    public static final String TYPE = "ohlcv.bar";

    @JsonCreator
    public OhlcvBarEvent {
    }

    public static OhlcvBarEvent of(String ticker, LocalDate sessionDate, BigDecimal open,
                                   BigDecimal high, BigDecimal low, BigDecimal close, long volume) {
        return new OhlcvBarEvent(UUID.randomUUID().toString(), Instant.now(),
                ticker, sessionDate, open, high, low, close, volume);
    }

    @Override
    @JsonProperty("eventType")
    public String eventType() {
        return TYPE;
    }
}
