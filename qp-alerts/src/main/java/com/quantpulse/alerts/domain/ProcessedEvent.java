package com.quantpulse.alerts.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** Events already handled, to skip duplicates. */
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
}
