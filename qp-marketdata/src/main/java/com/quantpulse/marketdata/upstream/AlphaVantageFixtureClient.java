package com.quantpulse.marketdata.upstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Replays saved Alpha Vantage responses from ops/fixtures/av-*.json, so dev and tests
 * never use the real key. Active in every profile except live.
 *
 * Fixtures go through the same parsing as live responses, error check included.
 * No simulated movement: FX rates are stored once a day, so a fixed snapshot is fine.
 * Fixtures marked synthetic in _manifest.json are generated, not recorded.
 */
@Component
@Profile("!live")
public class AlphaVantageFixtureClient implements AlphaVantageClient {

    private static final Logger log = LoggerFactory.getLogger(AlphaVantageFixtureClient.class);

    private final ObjectMapper mapper;
    private final ResourceLoader resourceLoader;
    private final String fixtureLocation;

    public AlphaVantageFixtureClient(ObjectMapper mapper,
                                     ResourceLoader resourceLoader,
                                     @Value("${quantpulse.fixtures.location:file:../ops/fixtures/}") String fixtureLocation) {
        this.mapper = mapper;
        this.resourceLoader = resourceLoader;
        this.fixtureLocation = fixtureLocation.endsWith("/") ? fixtureLocation : fixtureLocation + "/";
    }

    @Override
    public String sourceName() {
        return "FIXTURE";
    }

    @Override
    public Optional<AlphaVantageDtos.FxRateDto> fetchFxRate(String fromCurrency, String toCurrency) {
        return read("av-fx-" + fromCurrency + "-" + toCurrency).flatMap(AlphaVantageDtos::parseFxRate);
    }

    /** full is ignored, a fixture is what was recorded. */
    @Override
    public List<AlphaVantageDtos.OhlcvPointDto> fetchDailySeries(String symbol, boolean full) {
        return read("av-daily-" + symbol).map(AlphaVantageDtos::parseDailySeries).orElseGet(List::of);
    }

    @Override
    public List<AlphaVantageDtos.FxDailyPointDto> fetchFxDaily(String fromCurrency, String toCurrency, boolean full) {
        return read("av-fxdaily-" + fromCurrency + "-" + toCurrency)
                .map(AlphaVantageDtos::parseFxDaily).orElseGet(List::of);
    }

    /**
     * SYNTHETIC if the manifest says the fixture was generated.
     * Read on every call so recording real data shows up without a restart.
     */
    @Override
    public String sourceFor(String dataset) {
        Resource manifest = resourceLoader.getResource(fixtureLocation + "_manifest.json");
        try (InputStream in = manifest.getInputStream()) {
            JsonNode entry = mapper.readTree(in).path("av-" + dataset);
            return entry.path("synthetic").asBoolean(false) ? "SYNTHETIC" : sourceName();
        } catch (IOException e) {
            return sourceName();
        }
    }

    private Optional<JsonNode> read(String slug) {
        Resource resource = resourceLoader.getResource(fixtureLocation + slug + ".json");
        if (!resource.exists()) {
            log.warn("[AV-FIXTURES] no fixture {} at {}", slug, fixtureLocation);
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode body = mapper.readTree(in);
            Optional<AlphaVantageDtos.SoftFailure> failure = AlphaVantageDtos.detectSoftFailure(body);
            if (failure.isPresent()) {
                log.warn("[AV-FIXTURES] fixture {} is a recorded {} response: {}",
                        slug, failure.get().kind(), failure.get().message());
                return Optional.empty();
            }
            return Optional.of(body);
        } catch (IOException e) {
            log.warn("[AV-FIXTURES] could not read {}: {}", slug, e.getMessage());
            return Optional.empty();
        }
    }
}
