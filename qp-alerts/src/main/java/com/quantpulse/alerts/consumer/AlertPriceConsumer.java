package com.quantpulse.alerts.consumer;

import com.quantpulse.alerts.domain.ProcessedEvent;
import com.quantpulse.alerts.repository.ProcessedEventRepository;
import com.quantpulse.alerts.service.AlertEvaluationService;
import com.quantpulse.common.event.PriceTickEvent;
import com.quantpulse.common.event.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Sends each price tick to the rule engine.
 *
 * Uses its own queue on the same exchange as qp-portfolio, so both get every tick.
 * Adding this service didn't need any change in qp-marketdata.
 */
@Component
public class AlertPriceConsumer {

    private static final Logger log = LoggerFactory.getLogger(AlertPriceConsumer.class);
    private static final String CONSUMER_NAME = "alerts-evaluation";

    private final AlertEvaluationService evaluation;
    private final ProcessedEventRepository processed;

    public AlertPriceConsumer(AlertEvaluationService evaluation, ProcessedEventRepository processed) {
        this.evaluation = evaluation;
        this.processed = processed;
    }

    @RabbitListener(queues = Topology.Q_ALERTS_EVALUATION, concurrency = "2-4")
    @Transactional
    public void onPriceTick(PriceTickEvent event,
                            @Header(name = "x-event-id", required = false) String headerEventId) {
        String raw = headerEventId != null ? headerEventId : event.eventId();
        UUID eventId;
        try {
            eventId = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unusable event id: " + raw, e);
        }

        // Skipping duplicates matters here: the same tick twice would send the user two alerts.
        try {
            processed.saveAndFlush(new ProcessedEvent(eventId, CONSUMER_NAME));
        } catch (DataIntegrityViolationException e) {
            log.debug("[ALERTS] duplicate event {}", eventId);
            return;
        }

        evaluation.evaluate(event.ticker(), event.price(), event.occurredAt());
    }
}
