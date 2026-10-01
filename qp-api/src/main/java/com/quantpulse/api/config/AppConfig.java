package com.quantpulse.api.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AppConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalCustomizer() {
        return builder -> builder
                .featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    public RestClient marketDataClient(@Value("${quantpulse.services.marketdata}") String url) {
        return client(url);
    }

    @Bean
    public RestClient portfolioClient(@Value("${quantpulse.services.portfolio}") String url) {
        return client(url);
    }

    @Bean
    public RestClient alertsClient(@Value("${quantpulse.services.alerts}") String url) {
        return client(url);
    }

    /**
     * qp-insights. Regenerating waits for the model (up to 45s), so the timeout is longer than the usual 5s.
     */
    @Bean
    public RestClient insightsClient(@Value("${quantpulse.services.insights}") String url) {
        return RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory(Duration.ofSeconds(2), Duration.ofSeconds(60)))
                .build();
    }

    /**
     * qp-quant does heavier work (correlation matrix, backtests) that takes seconds, so it gets a longer timeout.
     */
    @Bean
    public RestClient quantClient(@Value("${quantpulse.services.quant}") String url) {
        return RestClient.builder()
                .baseUrl(url)
                .requestFactory(requestFactory(Duration.ofSeconds(3), Duration.ofSeconds(90)))
                .build();
    }

    /** Short timeouts so a slow service fails fast and the page still loads. */
    private RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .build();
    }

    /**
     * Request factory forced to HTTP/1.1.
     *
     * Java's HttpClient defaults to HTTP/2 and sends an h2c upgrade. Tomcat handles it,
     * but Uvicorn (qp-quant) doesn't, and the POST body arrived empty. Took a while to find:
     * it only showed up when dumping the headers FastAPI received. We don't need HTTP/2 anyway.
     */
    private ClientHttpRequestFactory requestFactory(Duration connect, Duration read) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(connect)
                        .build());
        factory.setReadTimeout(read);
        return factory;
    }
}
