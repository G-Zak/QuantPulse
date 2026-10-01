package com.quantpulse.portfolio.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An event we already handled.
 * The event id is the primary key, so a duplicate insert fails. Stored in the DB so it
 * works after a restart and across instances.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(nullable = false, length = 64)
    private String consumer;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, String consumer) {
        this.eventId = eventId;
        this.consumer = consumer;
    }

    public UUID getEventId() { return eventId; }
    public String getConsumer() { return consumer; }
    public Instant getProcessedAt() { return processedAt; }
}
