package com.quantpulse.insights;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Insights service: writes the daily market brief.
 *
 * It only owns the insights schema. Market data comes over HTTP from qp-marketdata and
 * qp-alerts. The model is treated like any other limited API: daily budget, circuit breaker,
 * and a template as fallback.
 */
@SpringBootApplication
public class InsightsApplication {
    public static void main(String[] args) {
        SpringApplication.run(InsightsApplication.class, args);
    }
}
