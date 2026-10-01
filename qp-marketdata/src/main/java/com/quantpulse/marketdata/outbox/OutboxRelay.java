package com.quantpulse.marketdata.outbox;

import com.quantpulse.common.event.Topology;
import com.quantpulse.marketdata.domain.OutboxEvent;
import com.quantpulse.marketdata.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes outbox rows to RabbitMQ.
 *
 * If the relay crashes after publishing but before marking the row, the event is sent
 * again on restart. That's fine, consumers skip duplicates by eventId.
 *
 * We poll the table once a second. Debezium (reading the WAL) would avoid polling,
 * but it's more to run and not needed at this volume.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final AtomicLong backlogGauge = new AtomicLong();

    public OutboxRelay(OutboxEventRepository repository,
                       RabbitTemplate rabbitTemplate,
                       MeterRegistry meterRegistry,
                       @Value("${quantpulse.outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = batchSize;

        Gauge.builder("quantpulse.outbox.backlog", backlogGauge, AtomicLong::get)
                .description("Outbox events awaiting publication")
                .register(meterRegistry);
    }

    /**
     * @SchedulerLock so two instances don't publish the same batch.
     * SKIP LOCKED also stops them from taking the same rows.
     */
    @Scheduled(fixedDelayString = "${quantpulse.outbox.poll-ms:1000}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT30S", lockAtLeastFor = "PT1S")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.claimUnpublished(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            backlogGauge.set(0);
            return;
        }

        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                publish(event);
                event.markPublished();
                published++;
            } catch (Exception e) {
                // Leave published_at empty so the next poll retries. The attempt count and last error
                // stay on the row so a broken event is easy to spot.
                event.markFailed(e.toString());
                log.error("[OUTBOX] publish failed for eventId={} attempts={}: {}",
                        event.getEventId(), event.getAttempts(), e.getMessage());
            }
        }

        repository.saveAll(batch);
        backlogGauge.set(repository.countUnpublished());
        if (published > 0) {
            log.debug("[OUTBOX] published {}/{} events", published, batch.size());
        }
    }

    private void publish(OutboxEvent event) {
        org.springframework.amqp.core.Message message = MessageBuilder
                .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                // Persistent, otherwise a broker restart loses the message even if the queue is durable.
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(event.getEventId().toString())
                // Consumers save this in their dedup table.
                .setHeader("x-event-id", event.getEventId().toString())
                .setHeader("x-event-type", event.getEventType())
                .setHeader("x-aggregate-id", event.getAggregateId())
                .setHeader("x-occurred-at", event.getOccurredAt().toString())
                .build();

        rabbitTemplate.send(Topology.MARKET_EXCHANGE, event.getRoutingKey(), message);
    }

    /** Cleans up old published rows so the table doesn't keep growing. */
    @Scheduled(cron = "${quantpulse.outbox.purge-cron:0 30 2 * * *}")
    @SchedulerLock(name = "outbox-purge", lockAtMostFor = "PT5M")
    @Transactional
    public void purgePublished() {
        int deleted = repository.purgePublishedBefore(Instant.now().minus(7, ChronoUnit.DAYS));
        if (deleted > 0) {
            log.info("[OUTBOX] purged {} published events older than 7 days", deleted);
        }
    }
}
