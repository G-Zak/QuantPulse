package com.quantpulse.insights.brief;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantpulse.insights.facts.FactCollector;
import com.quantpulse.insights.facts.MarketFacts;
import com.quantpulse.insights.llm.BriefDraft;
import com.quantpulse.insights.llm.ClaudeClient;
import com.quantpulse.insights.llm.LlmBudgetGovernor;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Writes one brief.
 *
 *   collect facts -> API key set? -> budget left? -> Claude -> validator -> CLAUDE brief
 *   if any step says no, we save the TEMPLATE brief with the reason.
 *
 * A refused draft is saved next to the template, so we can always see why the plain one was used.
 */
@Service
public class BriefService {

    private static final Logger log = LoggerFactory.getLogger(BriefService.class);

    private final FactCollector collector;
    private final ClaudeClient claude;
    private final LlmBudgetGovernor budget;
    private final BriefValidator validator;
    private final TemplateBriefWriter template;
    private final MarketBriefRepository briefs;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int dailyLimit;

    public BriefService(FactCollector collector, ClaudeClient claude, LlmBudgetGovernor budget,
                        BriefValidator validator, TemplateBriefWriter template, MarketBriefRepository briefs,
                        ObjectMapper mapper, MeterRegistry meters, Clock clock,
                        @Value("${quantpulse.insights.daily-limit:5}") int dailyLimit) {
        this.collector = collector;
        this.claude = claude;
        this.budget = budget;
        this.validator = validator;
        this.template = template;
        this.briefs = briefs;
        this.mapper = mapper;
        this.meters = meters;
        this.clock = clock;
        this.dailyLimit = dailyLimit;
    }

    /** @return the saved brief, or empty if there were no market facts */
    public Optional<MarketBrief> generate(MarketBrief.Trigger trigger) {
        MarketFacts facts = collector.collect();
        if (facts.indices().isEmpty() && facts.breadth() == null) {
            log.warn("[BRIEF] no market data available; not writing a brief ({})", trigger);
            return Optional.empty();
        }
        JsonNode factsNode = mapper.valueToTree(facts);
        String factsJson = json(factsNode);
        LocalDate today = LocalDate.now(clock);

        String fallback;
        BriefDraft refused = null;
        if (!claude.isConfigured()) {
            fallback = "AI disabled: ANTHROPIC_API_KEY is not set.";
        } else if (!budget.tryAcquire()) {
            fallback = "Today's AI budget of " + dailyLimit + " calls is spent; it resets at 00:00 UTC.";
        } else {
            try {
                BriefDraft draft = claude.write(facts);
                BriefValidator.Verdict verdict = validator.validate(draft, factsNode);
                if (verdict.accepted()) {
                    count("CLAUDE", "accepted");
                    return Optional.of(briefs.save(new MarketBrief(today, trigger, MarketBrief.Source.CLAUDE,
                            draft.model(), draft.headline(), draft.body(), json(verdict.factsUsed()), factsJson)
                            .withUsage(draft.inputTokens(), draft.outputTokens(), draft.latencyMs())));
                }
                refused = draft;
                fallback = "Model draft refused by validation: " + String.join("; ", verdict.problems());
                count("TEMPLATE", "refused");
            } catch (CallNotPermittedException e) {
                fallback = "AI provider circuit breaker is open after repeated failures; retrying later.";
                count("TEMPLATE", "circuit_open");
            } catch (Exception e) {
                fallback = "AI call failed: " + e.getMessage();
                count("TEMPLATE", "error");
            }
        }

        log.info("[BRIEF] template brief ({}): {}", trigger, fallback);
        TemplateBriefWriter.Written w = template.write(facts);
        MarketBrief brief = new MarketBrief(today, trigger, MarketBrief.Source.TEMPLATE, null, w.headline(), w.body(),
                json(w.factsUsed()), factsJson)
                .withFallback(fallback, refused == null ? null : refused.headline() + "\n\n" + refused.body());
        if (refused != null) {
            brief.withUsage(refused.inputTokens(), refused.outputTokens(), refused.latencyMs());
        }
        return Optional.of(briefs.save(brief));
    }

    public Optional<MarketBrief> latest() {
        return briefs.findFirstByOrderByGeneratedAtDesc();
    }

    public boolean hasBriefFor(LocalDate date) {
        return briefs.existsByBriefDate(date);
    }

    public boolean aiEnabled() {
        return claude.isConfigured();
    }

    public String model() {
        return claude.model();
    }

    public int budgetRemaining() {
        return budget.remainingToday();
    }

    private void count(String source, String outcome) {
        meters.counter("quantpulse.insights.briefs", "source", source, "outcome", outcome).increment();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
