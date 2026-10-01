package com.quantpulse.insights.brief;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Internal API, the browser goes through qp-api. */
@RestController
@RequestMapping("/api/v1/brief")
public class BriefController {

    private final BriefService service;
    private final ObjectMapper mapper;

    public BriefController(BriefService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public record BriefView(Long id, LocalDate date, Instant generatedAt, String trigger, String source,
                            String model, String headline, String body, List<String> factsUsed,
                            JsonNode facts, boolean sampleData, String fallbackReason,
                            Integer inputTokens, Integer outputTokens, Integer latencyMs,
                            boolean aiEnabled, int budgetRemaining) {
    }

    @GetMapping("/latest")
    public ResponseEntity<BriefView> latest() {
        return service.latest().map(b -> ResponseEntity.ok(view(b)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Uses one call of the daily AI budget when AI is on. */
    @PostMapping("/regenerate")
    public ResponseEntity<?> regenerate() {
        return service.generate(MarketBrief.Trigger.MANUAL)
                .<ResponseEntity<?>>map(b -> ResponseEntity.ok(view(b)))
                .orElse(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "NO_MARKET_DATA", "message", "qp-marketdata returned no data")));
    }

    private BriefView view(MarketBrief b) {
        try {
            JsonNode facts = mapper.readTree(b.getFacts());
            List<String> used = mapper.readValue(b.getFactsUsed(), new TypeReference<>() {});
            return new BriefView(b.getId(), b.getBriefDate(), b.getGeneratedAt(), b.getTrigger().name(),
                    b.getSource().name(), b.getModel(), b.getHeadline(), b.getBody(), used, facts,
                    facts.path("sampleData").asBoolean(false), b.getFallbackReason(),
                    b.getInputTokens(), b.getOutputTokens(), b.getLatencyMs(),
                    service.aiEnabled(), service.budgetRemaining());
        } catch (Exception e) {
            throw new IllegalStateException("stored brief " + b.getId() + " is unreadable", e);
        }
    }
}
