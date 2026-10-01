package com.quantpulse.alerts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Alerts service. Owns the alerts schema and only gets prices through events. */
@SpringBootApplication
@EnableScheduling
public class AlertsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlertsApplication.class, args);
    }
}
