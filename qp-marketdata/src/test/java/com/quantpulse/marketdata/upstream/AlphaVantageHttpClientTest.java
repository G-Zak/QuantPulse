package com.quantpulse.marketdata.upstream;

import com.quantpulse.marketdata.quota.AlphaVantageQuotaGovernor;
import com.quantpulse.marketdata.quota.AlphaVantageQuotaGovernor.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Tests the client without Spring, so @CircuitBreaker and @Retry do nothing and
 * exceptions come out directly. We test what the client does, not Resilience4j.
 */
class AlphaVantageHttpClientTest {

    private MockRestServiceServer server;
    private AlphaVantageQuotaGovernor quota;
    private AlphaVantageHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://www.alphavantage.co")
                .defaultUriVariables(Map.of("apikey", "test-key"));
        server = MockRestServiceServer.bindTo(builder).build();
        quota = mock(AlphaVantageQuotaGovernor.class);
        client = new AlphaVantageHttpClient(builder.build(), quota);
    }

    @Test
    @DisplayName("a granted call sends the key as a query parameter and parses the rate")
    void happyPath() {
        when(quota.tryAcquire(any(), eq(Priority.CRITICAL))).thenReturn(true);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.alphavantage.co/query")))
                .andExpect(queryParam("function", "CURRENCY_EXCHANGE_RATE"))
                .andExpect(queryParam("apikey", "test-key"))
                .andRespond(withSuccess("""
                        {"Realtime Currency Exchange Rate": {
                          "1. From_Currency Code": "USD", "3. To_Currency Code": "MAD",
                          "5. Exchange Rate": "9.18450000", "6. Last Refreshed": "2026-09-25 21:59:00",
                          "7. Time Zone": "UTC", "8. Bid Price": "9.1821", "9. Ask Price": "9.1869"}}
                        """, MediaType.APPLICATION_JSON));

        var rate = client.fetchFxRate("USD", "MAD");

        assertThat(rate).hasValueSatisfying(r -> assertThat(r.rate()).isEqualTo(new BigDecimal("9.18450000")));
        verify(quota).recordOutcome(eq("fx-USD-MAD"), eq("CURRENCY_EXCHANGE_RATE"), eq(200), anyLong());
        server.verify();
    }

    @Test
    @DisplayName("a 200 carrying a rate-limit message marks the budget exhausted and is not parsed as data")
    void softRateLimit() {
        when(quota.tryAcquire(any(), any())).thenReturn(true);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.alphavantage.co/query")))
                .andRespond(withSuccess("{\"Information\":\"standard API rate limit is 25 requests per day\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchFxRate("USD", "MAD"))
                .isInstanceOf(AlphaVantageHttpClient.UpstreamRefusedException.class);
        verify(quota).recordUpstreamRefusal("fx-USD-MAD");
    }

    @Test
    @DisplayName("an Error Message body is a rejected request, not a budget signal")
    void softRejection() {
        when(quota.tryAcquire(any(), any())).thenReturn(true);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.alphavantage.co/query")))
                .andRespond(withSuccess("{\"Error Message\":\"Invalid API call.\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchDailySeries("NOPE"))
                .isInstanceOf(AlphaVantageHttpClient.UpstreamRejectedException.class);
        verify(quota, never()).recordUpstreamRefusal(any());
    }

    @Test
    @DisplayName("a 5xx is an upstream fault — the retryable kind")
    void serverError() {
        when(quota.tryAcquire(any(), any())).thenReturn(true);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://www.alphavantage.co/query")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.fetchFxRate("EUR", "MAD"))
                .isInstanceOf(AlphaVantageHttpClient.UpstreamServerException.class);
    }

    @Test
    @DisplayName("a denied budget means no socket is opened at all")
    void quotaDeniedMakesNoRequest() {
        when(quota.tryAcquire(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> client.fetchFxRate("USD", "MAD"))
                .isInstanceOf(AlphaVantageHttpClient.QuotaExhaustedException.class);
        server.verify();  // no expectations set, so any request would fail here
    }
}
