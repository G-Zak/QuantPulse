package com.quantpulse.marketdata.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class AppConfig {

    /**
     * UTC because the API quota resets at midnight UTC.
     * Injecting the Clock also lets tests check the daily reset.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Without this Jackson reads decimals as double and we lose precision. */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalCustomizer() {
        return builder -> builder
                .featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * Drahmi HTTP client.
     *
     * Needs a browser User-Agent: Cloudflare returns 403 to the default Java one,
     * which looks like an auth error.
     */
    @Bean
    public RestClient drahmiRestClient(@Value("${quantpulse.drahmi.base-url}") String baseUrl,
                                       @Value("${quantpulse.drahmi.api-key:}") String apiKey,
                                       @Value("${quantpulse.drahmi.timeout-seconds:20}") int timeoutSeconds) {
        // HTTP/1.1, see the note in qp-api's AppConfig (h2c upgrade drops the body on some servers).
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-API-Key", apiKey)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build();
    }

    /**
     * Alpha Vantage HTTP client.
     *
     * The key goes in the query string, so it's set once here as a default URI variable
     * instead of being passed around (where it could end up in logs).
     */
    @Bean
    public RestClient alphaVantageRestClient(@Value("${quantpulse.alphavantage.base-url}") String baseUrl,
                                             @Value("${quantpulse.alphavantage.api-key:}") String apiKey,
                                             @Value("${quantpulse.alphavantage.timeout-seconds:20}") int timeoutSeconds) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultUriVariables(java.util.Map.of("apikey", apiKey))
                .defaultHeader("Accept", "application/json")
                .build();
    }

    /**
     * L1 cache. The data is ~15 min delayed, so caching for less than that would just
     * waste API calls.
     */
    @Bean
    public CacheManager cacheManager(
            @Value("${quantpulse.cache.ttl-minutes:15}") int ttlMinutes,
            @Value("${quantpulse.cache.max-size:5000}") int maxSize) {
        CaffeineCacheManager manager = new CaffeineCacheManager(
                "instruments", "instrumentDetail", "indices", "sectors", "series", "overview");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(maxSize)
                .recordStats());
        return manager;
    }

    /** ShedLock uses the same database. */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        .usingDbTime()  // use the DB clock so server clock drift doesn't matter
                        .build());
    }
}
