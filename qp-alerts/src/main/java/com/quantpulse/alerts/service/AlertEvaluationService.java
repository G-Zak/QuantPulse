package com.quantpulse.alerts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantpulse.alerts.domain.AlertFiring;
import com.quantpulse.alerts.domain.AlertRule;
import com.quantpulse.alerts.domain.NotificationOutbox;
import com.quantpulse.alerts.repository.AlertFiringRepository;
import com.quantpulse.alerts.repository.AlertRuleRepository;
import com.quantpulse.alerts.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Checks all enabled rules of a ticker against a new price.
 *
 * The rule state, the firing and the notification are saved together, so we never
 * have a fired rule with no notification, or the other way round.
 */
@Service
public class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);

    private final AlertRuleRepository rules;
    private final AlertFiringRepository firings;
    private final NotificationOutboxRepository notifications;
    private final ObjectMapper objectMapper;
    private final Counter evaluated;
    private final Counter fired;
    private final Counter suppressed;

    public AlertEvaluationService(AlertRuleRepository rules,
                                  AlertFiringRepository firings,
                                  NotificationOutboxRepository notifications,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        this.rules = rules;
        this.firings = firings;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
        this.evaluated = Counter.builder("quantpulse.alerts.evaluated")
                .description("Rules evaluated").register(meterRegistry);
        this.fired = Counter.builder("quantpulse.alerts.fired")
                .description("Rules that fired").register(meterRegistry);
        this.suppressed = Counter.builder("quantpulse.alerts.suppressed")
                .description("Conditions met but suppressed by hysteresis or cooldown")
                .register(meterRegistry);
    }

    @Transactional
    public int evaluate(String ticker, BigDecimal price, Instant observedAt) {
        List<AlertRule> candidates = rules.findByTickerAndEnabledTrue(ticker);
        if (candidates.isEmpty()) {
            return 0;
        }

        int firedCount = 0;
        for (AlertRule rule : candidates) {
            evaluated.increment();
            // Percent rules compare against the rule's last seen price.
            BigDecimal reference = rule.getLastPrice();
            boolean wasArmed = rule.isArmed();

            var trigger = rule.evaluate(price, reference, observedAt);
            rules.save(rule);

            if (trigger.isEmpty()) {
                if (wasArmed && !rule.isArmed()) {
                    suppressed.increment();
                }
                continue;
            }

            AlertFiring firing = firings.save(new AlertFiring(
                    rule.getId(), ticker, trigger.get().price(),
                    trigger.get().threshold(), trigger.get().reason()));

            notifications.save(new NotificationOutbox(
                    firing.getId(), "LOG", rule.getOwner(), payloadFor(rule, firing)));

            fired.increment();
            firedCount++;
            log.info("[ALERT] {} — {}", rule.getType(), trigger.get().reason());
        }
        return firedCount;
    }

    private String payloadFor(AlertRule rule, AlertFiring firing) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "ruleId", rule.getId().toString(),
                    "ticker", firing.getTicker(),
                    "type", rule.getType().name(),
                    "price", firing.getTriggeredPrice(),
                    "threshold", firing.getThreshold(),
                    "reason", firing.getReason(),
                    "firedAt", firing.getFiredAt().toString()));
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialise alert payload", e);
        }
    }
}
