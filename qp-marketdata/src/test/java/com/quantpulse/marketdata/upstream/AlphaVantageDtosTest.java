package com.quantpulse.marketdata.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parses the fixture files on disk, the same ones dev uses. */
class AlphaVantageDtosTest {

    private static final Path FIXTURES = Path.of("..", "ops", "fixtures");
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixture(String slug) throws IOException {
        return mapper.readTree(FIXTURES.resolve(slug + ".json").toFile());
    }

    @Nested
    @DisplayName("soft failures disguised as HTTP 200")
    class SoftFailures {

        @Test
        void recordedInformationBodyIsARateLimitRefusalNotData() throws IOException {
            // Copied from the real API: 200, no data, one message field.
            var failure = AlphaVantageDtos.detectSoftFailure(fixture("av-soft-failure-information"));
            assertThat(failure).hasValueSatisfying(f ->
                    assertThat(f.kind()).isEqualTo(AlphaVantageDtos.SoftFailure.Kind.RATE_LIMITED));
        }

        @Test
        void noteIsAlsoARateLimitRefusal() throws IOException {
            JsonNode body = mapper.readTree("{\"Note\":\"Thank you for using Alpha Vantage! Our standard API rate limit is 25 requests per day.\"}");
            assertThat(AlphaVantageDtos.detectSoftFailure(body))
                    .hasValueSatisfying(f -> assertThat(f.kind()).isEqualTo(AlphaVantageDtos.SoftFailure.Kind.RATE_LIMITED));
        }

        @Test
        void errorMessageIsARejectedRequest() throws IOException {
            JsonNode body = mapper.readTree("{\"Error Message\":\"Invalid API call.\"}");
            assertThat(AlphaVantageDtos.detectSoftFailure(body))
                    .hasValueSatisfying(f -> assertThat(f.kind()).isEqualTo(AlphaVantageDtos.SoftFailure.Kind.REJECTED));
        }

        @Test
        void aPremiumOnlyNoticeIsARejectedRequestNotASpentKey() throws IOException {
            // If this counted as a rate limit, one outputsize=full request would block the whole day.
            JsonNode body = mapper.readTree("{\"Information\":\"Thank you for using Alpha Vantage! This is a premium endpoint.\"}");
            assertThat(AlphaVantageDtos.detectSoftFailure(body))
                    .hasValueSatisfying(f -> assertThat(f.kind()).isEqualTo(AlphaVantageDtos.SoftFailure.Kind.REJECTED));
        }

        @Test
        void aRealDataBodyIsNotAFailure() throws IOException {
            assertThat(AlphaVantageDtos.detectSoftFailure(fixture("av-fx-USD-MAD"))).isEmpty();
            assertThat(AlphaVantageDtos.detectSoftFailure(fixture("av-daily-IBM"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        void fxRateKeepsEveryDecimalTheUpstreamSent() throws IOException {
            var dto = AlphaVantageDtos.parseFxRate(fixture("av-fx-USD-MAD")).orElseThrow();
            assertThat(dto.fromCurrency()).isEqualTo("USD");
            assertThat(dto.toCurrency()).isEqualTo("MAD");
            // String to BigDecimal directly, so trailing zeros are kept.
            assertThat(dto.rate()).isEqualTo(new BigDecimal("9.18450000"));
            assertThat(dto.rateDate()).isEqualTo(LocalDate.of(2026, 9, 25));
        }

        @Test
        void fxDailyHistoryIsParsedOldestFirstWithThePairFromTheMetadata() throws IOException {
            var points = AlphaVantageDtos.parseFxDaily(fixture("av-fxdaily-USD-MAD"));
            assertThat(points).hasSizeGreaterThan(250);
            assertThat(points).allSatisfy(p -> {
                assertThat(p.fromCurrency()).isEqualTo("USD");
                assertThat(p.toCurrency()).isEqualTo("MAD");
            });
            assertThat(points).isSortedAccordingTo((a, b) -> a.date().compareTo(b.date()));
            assertThat(points.get(points.size() - 1).close()).isEqualTo(new BigDecimal("9.18450"));
        }

        @Test
        void dailySeriesIsReturnedOldestFirst() throws IOException {
            List<AlphaVantageDtos.OhlcvPointDto> bars =
                    AlphaVantageDtos.parseDailySeries(fixture("av-daily-IBM"));
            assertThat(bars).hasSizeGreaterThan(50);
            assertThat(bars).isSortedAccordingTo((a, b) -> a.date().compareTo(b.date()));
            var last = bars.get(bars.size() - 1);
            assertThat(last.date()).isEqualTo(LocalDate.of(2026, 9, 25));
            assertThat(last.close()).isEqualTo(new BigDecimal("225.5100"));
        }
    }
}
