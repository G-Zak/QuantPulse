package com.quantpulse.alerts.api;

import com.quantpulse.alerts.domain.AlertFiring;
import com.quantpulse.alerts.domain.AlertRule;
import com.quantpulse.alerts.repository.AlertFiringRepository;
import com.quantpulse.alerts.repository.AlertRuleRepository;
import com.quantpulse.alerts.service.AlertEvaluationService;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertRuleRepository rules;
    private final AlertFiringRepository firings;
    private final AlertEvaluationService evaluation;

    public AlertController(AlertRuleRepository rules, AlertFiringRepository firings,
                           AlertEvaluationService evaluation) {
        this.rules = rules;
        this.firings = firings;
        this.evaluation = evaluation;
    }

    public record CreateRuleRequest(@NotNull String owner, @NotNull String ticker,
                                    @NotNull AlertRule.Type type, @NotNull BigDecimal threshold,
                                    BigDecimal hysteresisPct, Integer cooldownSeconds) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRule create(@RequestBody CreateRuleRequest req) {
        AlertRule rule = new AlertRule(req.owner(), req.ticker().toUpperCase(),
                req.type(), req.threshold());
        if (req.hysteresisPct() != null) {
            rule.setHysteresisPct(req.hysteresisPct());
        }
        if (req.cooldownSeconds() != null) {
            rule.setCooldownSeconds(req.cooldownSeconds());
        }
        return rules.save(rule);
    }

    @GetMapping
    public List<AlertRule> list(@RequestParam(required = false) String owner) {
        return owner == null ? rules.findAll() : rules.findByOwner(owner);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AlertRule> get(@PathVariable UUID id) {
        return rules.findById(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        rules.deleteById(id);
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<AlertRule> toggle(@PathVariable UUID id) {
        return rules.findById(id).map(r -> {
            r.setEnabled(!r.isEnabled());
            return ResponseEntity.ok(rules.save(r));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/firings")
    public List<AlertFiring> firings(@RequestParam(defaultValue = "50") int limit) {
        return firings.findAllByOrderByFiredAtDesc(PageRequest.of(0, Math.min(limit, 200)));
    }

    @GetMapping("/{id}/firings")
    public List<AlertFiring> firingsForRule(@PathVariable UUID id) {
        return firings.findByRuleIdOrderByFiredAtDesc(id);
    }

    /** Run the rules now instead of waiting for the next price tick. */
    @PostMapping("/evaluate")
    public Map<String, Object> evaluate(@RequestParam String ticker, @RequestParam BigDecimal price) {
        int fired = evaluation.evaluate(ticker.toUpperCase(), price, Instant.now());
        return Map.of("ticker", ticker.toUpperCase(), "price", price, "fired", fired);
    }
}
