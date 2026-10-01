package com.quantpulse.common.event;

import java.time.Instant;

/**
 * Base for every event sent between services.
 *
 * RabbitMQ delivers at least once, so a consumer can get the same event twice
 * (e.g. it crashed before acking). Consumers save eventId in a table with a unique
 * constraint and skip duplicates.
 *
 * occurredAt is when it happened at the producer, not when it was sent or received.
 * Consumers use it to drop old updates that arrive late.
 */
public interface DomainEvent {

    /** Unique id, used to skip duplicates. */
    String eventId();

    /** When it happened at the producer. */
    Instant occurredAt();

    /** Routing key suffix, e.g. price.tick */
    String eventType();
}
