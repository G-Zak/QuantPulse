package com.quantpulse.alerts.service;

import com.quantpulse.alerts.domain.NotificationOutbox;
import com.quantpulse.alerts.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends the notifications waiting in the outbox.
 *
 * For now it only logs them. Email or push would just be another implementation.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int MAX_ATTEMPTS = 5;

    private final NotificationOutboxRepository outbox;
    private final AtomicLong pendingGauge = new AtomicLong();

    public NotificationDispatcher(NotificationOutboxRepository outbox, MeterRegistry meterRegistry) {
        this.outbox = outbox;
        Gauge.builder("quantpulse.alerts.notifications.pending", pendingGauge, AtomicLong::get)
                .description("Notifications awaiting delivery").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${quantpulse.alerts.dispatch-ms:2000}")
    @Transactional
    public void dispatch() {
        List<NotificationOutbox> batch = outbox.findPending(PageRequest.of(0, 50));
        for (NotificationOutbox n : batch) {
            if (n.getAttempts() >= MAX_ATTEMPTS) {
                continue;  // give up, keep the row to look at later
            }
            try {
                deliver(n);
                n.markDelivered();
            } catch (Exception e) {
                n.markFailed(e.toString());
                log.warn("[NOTIFY] delivery failed for {}: {}", n.getId(), e.getMessage());
            }
        }
        if (!batch.isEmpty()) {
            outbox.saveAll(batch);
        }
        pendingGauge.set(outbox.countPending());
    }

    private void deliver(NotificationOutbox n) {
        log.info("[NOTIFY] -> {} via {}: {}", n.getRecipient(), n.getChannel(), n.getPayload());
    }
}
