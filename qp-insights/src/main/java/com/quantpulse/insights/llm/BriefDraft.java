package com.quantpulse.insights.llm;

import java.util.List;

/** What the model sent back, not checked yet. */
public record BriefDraft(String headline, String body, List<String> factsUsed,
                         String model, int inputTokens, int outputTokens, long latencyMs) {
}
