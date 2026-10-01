package com.quantpulse.marketdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Market data service. The only service that calls the Drahmi API and writes to the market schema.
 * Keeping all API calls here means the 100/day quota is enforced in one place.
 */
@SpringBootApplication
public class MarketDataApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketDataApplication.class, args);
    }
}
