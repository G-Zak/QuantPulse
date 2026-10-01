package com.quantpulse.portfolio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Portfolio service: trade ledger, cost basis, P&L and risk.
 * Owns the portfolio schema and can't read market, prices only come as events.
 */
@SpringBootApplication
@EnableScheduling
public class PortfolioApplication {
    public static void main(String[] args) {
        SpringApplication.run(PortfolioApplication.class, args);
    }
}
