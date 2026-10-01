package com.quantpulse.insights.brief;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "market_brief")
public class MarketBrief {

    public enum Source { CLAUDE, TEMPLATE }

    public enum Trigger { SCHEDULED, CATCH_UP, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "brief_date", nullable = false)
    private LocalDate briefDate;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_kind", nullable = false, length = 16)
    private Trigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(length = 64)
    private String model;

    @Column(nullable = false, columnDefinition = "text")
    private String headline;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "facts_used", nullable = false, columnDefinition = "text")
    private String factsUsed;

    @Column(nullable = false, columnDefinition = "text")
    private String facts;

    @Column(name = "fallback_reason", columnDefinition = "text")
    private String fallbackReason;

    @Column(name = "rejected_draft", columnDefinition = "text")
    private String rejectedDraft;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    protected MarketBrief() {
    }

    public MarketBrief(LocalDate briefDate, Trigger trigger, Source source, String model, String headline,
                       String body, String factsUsed, String facts) {
        this.briefDate = briefDate;
        this.trigger = trigger;
        this.source = source;
        this.model = model;
        this.headline = headline;
        this.body = body;
        this.factsUsed = factsUsed;
        this.facts = facts;
    }

    public MarketBrief withFallback(String reason, String rejectedDraft) {
        this.fallbackReason = reason;
        this.rejectedDraft = rejectedDraft;
        return this;
    }

    public MarketBrief withUsage(int inputTokens, int outputTokens, long latencyMs) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = (int) latencyMs;
        return this;
    }

    public Long getId() { return id; }
    public LocalDate getBriefDate() { return briefDate; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Trigger getTrigger() { return trigger; }
    public Source getSource() { return source; }
    public String getModel() { return model; }
    public String getHeadline() { return headline; }
    public String getBody() { return body; }
    public String getFactsUsed() { return factsUsed; }
    public String getFacts() { return facts; }
    public String getFallbackReason() { return fallbackReason; }
    public String getRejectedDraft() { return rejectedDraft; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public Integer getLatencyMs() { return latencyMs; }
}
