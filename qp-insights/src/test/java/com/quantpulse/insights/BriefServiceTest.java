package com.quantpulse.insights;

import com.quantpulse.insights.brief.BriefService;
import com.quantpulse.insights.brief.BriefValidator;
import com.quantpulse.insights.brief.MarketBrief;
import com.quantpulse.insights.brief.MarketBriefRepository;
import com.quantpulse.insights.brief.TemplateBriefWriter;
import com.quantpulse.insights.facts.FactCollector;
import com.quantpulse.insights.facts.MarketFacts;
import com.quantpulse.insights.llm.BriefDraft;
import com.quantpulse.insights.llm.ClaudeClient;
import com.quantpulse.insights.llm.LlmBudgetGovernor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every fallback path still publishes a brief, and the template says why. */
class BriefServiceTest {

    private FactCollector collector;
    private ClaudeClient claude;
    private LlmBudgetGovernor budget;
    private BriefService service;

    @BeforeEach
    void setUp() {
        collector = mock(FactCollector.class);
        claude = mock(ClaudeClient.class);
        budget = mock(LlmBudgetGovernor.class);
        MarketBriefRepository repo = mock(MarketBriefRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(collector.collect()).thenReturn(Facts.sample(false));
        service = new BriefService(collector, claude, budget, new BriefValidator(), new TemplateBriefWriter(), repo,
                Facts.MAPPER, new SimpleMeterRegistry(),
                Clock.fixed(Instant.parse("2026-09-28T02:00:00Z"), ZoneOffset.UTC), 5);
    }

    private static BriefDraft draft(String body) {
        return new BriefDraft("MASI down 0.35%", body, List.of(), "claude-sonnet-5", 1000, 200, 900);
    }

    @Test
    @DisplayName("no API key: template, and no budget is touched")
    void noKey() {
        when(claude.isConfigured()).thenReturn(false);

        MarketBrief b = service.generate(MarketBrief.Trigger.SCHEDULED).orElseThrow();

        assertThat(b.getSource()).isEqualTo(MarketBrief.Source.TEMPLATE);
        assertThat(b.getFallbackReason()).contains("ANTHROPIC_API_KEY");
        verify(budget, never()).tryAcquire();
    }

    @Test
    @DisplayName("budget spent: template, and the model is never called")
    void budgetSpent() {
        when(claude.isConfigured()).thenReturn(true);
        when(budget.tryAcquire()).thenReturn(false);

        MarketBrief b = service.generate(MarketBrief.Trigger.MANUAL).orElseThrow();

        assertThat(b.getFallbackReason()).contains("budget of 5");
        verify(claude, never()).write(any());
    }

    @Test
    @DisplayName("a valid draft is published as the Claude brief, with its token usage")
    void accepted() {
        when(claude.isConfigured()).thenReturn(true);
        when(budget.tryAcquire()).thenReturn(true);
        when(claude.write(any())).thenReturn(draft(
                "The MASI fell 0.35% to 17839.02, as 44 stocks declined and 31 advanced. MANAGEM rose 3.21%."));

        MarketBrief b = service.generate(MarketBrief.Trigger.SCHEDULED).orElseThrow();

        assertThat(b.getSource()).isEqualTo(MarketBrief.Source.CLAUDE);
        assertThat(b.getInputTokens()).isEqualTo(1000);
        assertThat(b.getFallbackReason()).isNull();
    }

    @Test
    @DisplayName("an invented number: template published, refused draft kept for audit")
    void refused() {
        when(claude.isConfigured()).thenReturn(true);
        when(budget.tryAcquire()).thenReturn(true);
        when(claude.write(any())).thenReturn(draft(
                "The MASI fell 0.35% on turnover of 412 million dirhams, its busiest day in a month."));

        MarketBrief b = service.generate(MarketBrief.Trigger.SCHEDULED).orElseThrow();

        assertThat(b.getSource()).isEqualTo(MarketBrief.Source.TEMPLATE);
        assertThat(b.getFallbackReason()).contains("412");
        assertThat(b.getRejectedDraft()).contains("412 million");
    }

    @Test
    @DisplayName("provider failure: template, with the error as the reason")
    void providerDown() {
        when(claude.isConfigured()).thenReturn(true);
        when(budget.tryAcquire()).thenReturn(true);
        when(claude.write(any())).thenThrow(new ClaudeClient.LlmOverloadedException("anthropic 529"));

        MarketBrief b = service.generate(MarketBrief.Trigger.SCHEDULED).orElseThrow();

        assertThat(b.getSource()).isEqualTo(MarketBrief.Source.TEMPLATE);
        assertThat(b.getFallbackReason()).contains("529");
    }

    @Test
    @DisplayName("no market data at all: nothing is written, rather than a brief about nothing")
    void noData() {
        when(collector.collect()).thenReturn(new MarketFacts(Facts.sample(false).asOf(), "UNKNOWN", true,
                List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(), null, null, List.of()));

        assertThat(service.generate(MarketBrief.Trigger.CATCH_UP)).isEmpty();
    }
}
