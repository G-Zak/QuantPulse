package com.quantpulse.marketdata.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * Tells whether the Casablanca exchange is open right now.
 *
 * We don't use /market/status: the recorded response says isOpen: true at 15:57
 * Casablanca time, after the close, and gives the wrong timezone. Trusting it would
 * spend calls on a closed market. We still log its answer.
 *
 * Africa/Casablanca instead of a fixed +01:00 because Morocco switches to UTC+0
 * during Ramadan.
 */
@Component
public class TradingCalendar {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    /**
     * Fixed-date public holidays. Religious holidays (Eids, Islamic New Year, Mawlid) move
     * every year and aren't handled here, they would need a calendar feed.
     */
    private static final Set<String> FIXED_HOLIDAYS = Set.of(
            "01-01",  // New Year
            "01-11",  // Proclamation of Independence
            "05-01",  // Labour Day
            "07-30",  // Throne Day
            "08-14",  // Oued Ed-Dahab
            "08-20",  // Revolution of the King and the People
            "08-21",  // Youth Day
            "11-06",  // Green March
            "11-18"   // Independence Day
    );

    private final Clock clock;
    private final LocalTime open;
    private final LocalTime close;

    public TradingCalendar(Clock clock,
                           @Value("${quantpulse.market.open:09:30}") String open,
                           @Value("${quantpulse.market.close:15:30}") String close) {
        this.clock = clock;
        this.open = LocalTime.parse(open);
        this.close = LocalTime.parse(close);
    }

    public boolean isTradingNow() {
        ZonedDateTime local = ZonedDateTime.now(clock).withZoneSameInstant(CASABLANCA);
        return isTradingDay(local.toLocalDate())
                && !local.toLocalTime().isBefore(open)
                && !local.toLocalTime().isAfter(close);
    }

    public boolean isTradingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        return !FIXED_HOLIDAYS.contains(String.format("%02d-%02d",
                date.getMonthValue(), date.getDayOfMonth()));
    }

    /** Local time at the exchange, for logs and the UI. */
    public ZonedDateTime nowInCasablanca() {
        return ZonedDateTime.now(clock).withZoneSameInstant(CASABLANCA);
    }

    public String sessionDescription() {
        return open + "–" + close + " " + CASABLANCA.getId();
    }
}
