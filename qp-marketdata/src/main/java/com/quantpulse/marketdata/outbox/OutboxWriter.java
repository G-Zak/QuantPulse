package com.quantpulse.marketdata.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantpulse.common.event.DomainEvent;
import com.quantpulse.marketdata.domain.OutboxEvent;
import com.quantpulse.marketdata.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds an event to the outbox.
 *
 * MANDATORY: it refuses to run outside a transaction. The whole point is that the event
 * and the change are saved together, so calling it without a transaction should fail.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, String aggregateId, String routingKey, DomainEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            repository.save(new OutboxEvent(
                    aggregateType, aggregateId, event.eventType(), routingKey, payload));
        } catch (JsonProcessingException e) {
            // Can't serialise the event, that's a bug. Roll back so the change isn't saved without its event.
            throw new IllegalStateException(
                    "cannot serialise event " + event.eventType() + " for " + aggregateId, e);
        }
    }
}
