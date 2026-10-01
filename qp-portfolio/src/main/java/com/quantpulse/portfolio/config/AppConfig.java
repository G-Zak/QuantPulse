package com.quantpulse.portfolio.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Without USE_BIG_DECIMAL_FOR_FLOATS, Jackson reads decimals as double and we lose precision. */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalCustomizer() {
        return builder -> builder
                .featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** Gets OHLCV history from qp-marketdata's API, not its database. */
    @Bean
    public RestClient marketDataClient(
            @org.springframework.beans.factory.annotation.Value("${quantpulse.marketdata.base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
