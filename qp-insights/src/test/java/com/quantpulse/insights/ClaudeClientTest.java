package com.quantpulse.insights;

import com.quantpulse.insights.llm.ClaudeClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Checks the request and response format, without calling the API. */
class ClaudeClientTest {

    private MockRestServiceServer server;
    private ClaudeClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", "test-key")
                .defaultHeader("anthropic-version", "2023-06-01");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ClaudeClient(builder.build(), Facts.MAPPER, "test-key", "claude-sonnet-5", 900);
    }

    @Test
    @DisplayName("forces the publish_brief tool and reads the brief from the tool input")
    void forcedToolCall() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5"))
                .andExpect(jsonPath("$.tool_choice.type").value("tool"))
                .andExpect(jsonPath("$.tool_choice.name").value("publish_brief"))
                .andExpect(jsonPath("$.tools[0].input_schema.required[0]").value("headline"))
                .andRespond(withSuccess("""
                        {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5",
                         "stop_reason":"tool_use",
                         "content":[{"type":"tool_use","id":"toolu_1","name":"publish_brief",
                                     "input":{"headline":"MASI slips 0.35%","body":"The MASI fell 0.35%.",
                                              "facts_used":["indices[0].changePercent"]}}],
                         "usage":{"input_tokens":1234,"output_tokens":210}}
                        """, MediaType.APPLICATION_JSON));

        var draft = client.write(Facts.sample(false));

        assertThat(draft.headline()).isEqualTo("MASI slips 0.35%");
        assertThat(draft.factsUsed()).containsExactly("indices[0].changePercent");
        assertThat(draft.inputTokens()).isEqualTo(1234);
        assertThat(draft.outputTokens()).isEqualTo(210);
        server.verify();
    }

    @Test
    @DisplayName("529 overloaded is the provider being busy, not our request being wrong")
    void overloaded() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(529)));
        assertThatThrownBy(() -> client.write(Facts.sample(false)))
                .isInstanceOf(ClaudeClient.LlmOverloadedException.class);
    }

    @Test
    @DisplayName("a 400 is our fault: rejected, so the circuit breaker ignores it")
    void rejected() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}"));
        assertThatThrownBy(() -> client.write(Facts.sample(false)))
                .isInstanceOf(ClaudeClient.LlmRejectedException.class)
                .hasMessageContaining("400");
    }

    @Test
    @DisplayName("a response without the tool call is refused rather than scraped for text")
    void noToolCall() {
        server.expect(requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"Here is the brief..."}],"stop_reason":"max_tokens","usage":{}}
                        """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.write(Facts.sample(false)))
                .isInstanceOf(ClaudeClient.LlmRejectedException.class)
                .hasMessageContaining("max_tokens");
    }

    @Test
    @DisplayName("no key means not configured, so no request is ever built")
    void unconfigured() {
        var c = new ClaudeClient(RestClient.create(), Facts.MAPPER, " ", "claude-sonnet-5", 900);
        assertThat(c.isConfigured()).isFalse();
    }
}
