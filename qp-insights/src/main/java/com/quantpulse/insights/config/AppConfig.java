package com.quantpulse.insights.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RestClient marketDataClient(@Value("${quantpulse.services.marketdata}") String url) {
        return internal(url);
    }

    @Bean
    public RestClient alertsClient(@Value("${quantpulse.services.alerts}") String url) {
        return internal(url);
    }

    /**
     * Claude API client. The key is set here once as a header.
     * Long timeout: waiting is better than retrying and paying twice.
     */
    @Bean
    public RestClient anthropicRestClient(@Value("${quantpulse.anthropic.base-url}") String baseUrl,
                                          @Value("${quantpulse.anthropic.api-key:}") String apiKey,
                                          @Value("${quantpulse.anthropic.timeout-seconds:45}") int timeoutSeconds) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory(Duration.ofSeconds(10), Duration.ofSeconds(timeoutSeconds)))
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("content-type", "application/json")
                .build();
    }

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shedlock")
                .usingDbTime()
                .build());
    }

    private static RestClient internal(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory(Duration.ofSeconds(2), Duration.ofSeconds(10)))
                .build();
    }

    /** HTTP/1.1, same as in qp-api (avoids the h2c upgrade issue). */
    private static JdkClientHttpRequestFactory factory(Duration connect, Duration read) {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connect)
                .build());
        f.setReadTimeout(read);
        return f;
    }
}
