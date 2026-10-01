package com.quantpulse.insights.brief;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
public class BriefScheduler {

    private final BriefService service;
    private final Clock clock;

    public BriefScheduler(BriefService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /** Every night, after the 01:30 UTC Alpha Vantage job so FX and benchmarks are up to date. */
    @Scheduled(cron = "${quantpulse.insights.cron:0 45 1 * * *}", zone = "UTC")
    @SchedulerLock(name = "brief-nightly", lockAtMostFor = "PT5M")
    public void nightly() {
        service.generate(MarketBrief.Trigger.SCHEDULED);
    }

    /**
     * In case the nightly run was missed (service down, or marketdata not ready at first start).
     * Runs every 10 minutes but only writes if today has no brief, so at most one model call a day.
     */
    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT10M")
    @SchedulerLock(name = "brief-catch-up", lockAtMostFor = "PT5M")
    public void catchUp() {
        if (!service.hasBriefFor(LocalDate.now(clock))) {
            service.generate(MarketBrief.Trigger.CATCH_UP);
        }
    }
}
