package com.quantpulse.insights.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantpulse.insights.facts.MarketFacts;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls Claude (Messages API) to write the brief.
 *
 * We force a tool call (publish_brief with a JSON schema), so the answer comes back
 * as an object with headline, body and facts_used instead of free text.
 *
 * 429/500/529 mean the API is busy and count for the circuit breaker. Other 4xx are
 * our fault and are ignored by it. No retries: each call may be billed. On failure the
 * template is used and a logged-in user can regenerate.
 */
@Component
public class ClaudeClient {

    static final String TOOL = "publish_brief";

    static final String SYSTEM = """
            You write the daily market brief for QuantPulse, a dashboard for investors in the \
            Casablanca Stock Exchange (Bourse de Casablanca). Readers are Moroccan retail investors.

            Rules — a brief that breaks any of them is discarded automatically:
            1. Use ONLY numbers that appear in the facts JSON, copied exactly as written there \
            (same decimals, no rounding, no thousands separators). Do not compute differences, \
            sums, averages or ratios. If a number is not in the facts, write around it in words.
            2. No forecasts, no predictions, no advice. Never tell the reader to buy, sell or hold.
            3. If dataNotes is not empty, say plainly in the body that some figures are sample \
            data, naming which.
            4. English. Headline under 90 characters. Body 4 to 5 sentences, one paragraph.
            5. Lead with what moved the market today (the MASI index, breadth, the biggest movers), \
            then the dirham against the dollar and euro, then the one-year comparison with global \
            markets if present, then triggered alerts if any.
            6. In facts_used, list the JSON paths you drew numbers from, like "indices[0].changePercent".
            """;

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public ClaudeClient(RestClient anthropicRestClient, ObjectMapper mapper,
                        @Value("${quantpulse.anthropic.api-key:}") String apiKey,
                        @Value("${quantpulse.anthropic.model}") String model,
                        @Value("${quantpulse.anthropic.max-tokens:900}") int maxTokens) {
        this.client = anthropicRestClient;
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    @CircuitBreaker(name = "anthropic")
    public BriefDraft write(MarketFacts facts) {
        Map<String, Object> request = request(facts);
        long started = System.nanoTime();
        JsonNode response = client.post()
                .uri("/v1/messages")
                .body(request)
                .exchange((req, res) -> {
                    int status = res.getStatusCode().value();
                    if (status == 429 || status == 529 || res.getStatusCode().is5xxServerError()) {
                        throw new LlmOverloadedException("anthropic " + status);
                    }
                    if (res.getStatusCode().isError()) {
                        throw new LlmRejectedException("anthropic " + status + ": "
                                + new String(res.getBody().readAllBytes()));
                    }
                    return res.bodyTo(JsonNode.class);
                });
        long latency = (System.nanoTime() - started) / 1_000_000;
        return parse(response, latency);
    }

    Map<String, Object> request(MarketFacts facts) {
        String factsJson;
        try {
            factsJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts);
        } catch (Exception e) {
            throw new IllegalStateException("facts not serialisable", e);
        }
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "headline", Map.of("type", "string"),
                        "body", Map.of("type", "string"),
                        "facts_used", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required", List.of("headline", "body", "facts_used"));
        return Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", SYSTEM,
                "tools", List.of(Map.of(
                        "name", TOOL,
                        "description", "Publish today's market brief to the dashboard.",
                        "input_schema", schema)),
                "tool_choice", Map.of("type", "tool", "name", TOOL),
                "messages", List.of(Map.of("role", "user", "content",
                        "Facts for " + facts.asOf() + ":\n```json\n" + factsJson + "\n```\nWrite today's brief.")));
    }

    BriefDraft parse(JsonNode response, long latencyMs) {
        JsonNode input = null;
        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && TOOL.equals(block.path("name").asText())) {
                input = block.path("input");
            }
        }
        if (input == null || !input.hasNonNull("headline") || !input.hasNonNull("body")) {
            throw new LlmRejectedException("response had no " + TOOL + " call (stop_reason="
                    + response.path("stop_reason").asText() + ")");
        }
        List<String> used = new ArrayList<>();
        input.path("facts_used").forEach(n -> used.add(n.asText()));
        JsonNode usage = response.path("usage");
        return new BriefDraft(input.path("headline").asText().trim(), input.path("body").asText().trim(), used,
                response.path("model").asText(model), usage.path("input_tokens").asInt(),
                usage.path("output_tokens").asInt(), latencyMs);
    }

    /** API busy or failing. Counted by the circuit breaker. */
    public static class LlmOverloadedException extends RuntimeException {
        public LlmOverloadedException(String message) {
            super(message);
        }
    }

    /** Bad request, key, or answer format. Retrying won't help. */
    public static class LlmRejectedException extends RuntimeException {
        public LlmRejectedException(String message) {
            super(message);
        }
    }
}
