package com.quantpulse.marketdata.fx;

import com.quantpulse.common.money.Money;
import com.quantpulse.marketdata.domain.FxRate;
import com.quantpulse.marketdata.ingestion.FxRateIngestionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Internal FX endpoints (only qp-api is public). Reading costs no API calls, only the ops refresh does.
 */
@RestController
@RequestMapping("/api/v1")
public class FxController {

    private final FxConversionService conversions;
    private final FxRateIngestionService ingestion;
    private final Clock clock;

    public FxController(FxConversionService conversions, FxRateIngestionService ingestion, Clock clock) {
        this.conversions = conversions;
        this.ingestion = ingestion;
        this.clock = clock;
    }

    @GetMapping("/fx/convert")
    public ConversionView convert(@RequestParam BigDecimal amount,
                                  @RequestParam String from,
                                  @RequestParam String to,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        LocalDate date = asOf != null ? asOf : LocalDate.now(clock);
        FxRate rate = conversions.latestRate(from, to, date);
        Money converted = conversions.convert(Money.of(amount, from), to, date);
        // Return the rate and its date with the result, so the conversion can be checked.
        return new ConversionView(Money.of(amount, from), converted, rate.getRate(),
                rate.getRateDate(), rate.getSource());
    }

    /** Latest rate per pair with the daily change. pairs like USD-MAD,EUR-MAD */
    @GetMapping("/fx/rates")
    public List<FxConversionService.Quote> rates(@RequestParam(defaultValue = "USD-MAD,EUR-MAD") String pairs) {
        return java.util.Arrays.stream(pairs.split(","))
                .map(p -> p.trim().toUpperCase().split("-"))
                .filter(p -> p.length == 2)
                .flatMap(p -> conversions.quote(p[0], p[1]).stream())
                .toList();
    }

    /**
     * Run the daily FX refresh now: spot rate for each pair, then history for pairs that
     * don't have enough yet. In live mode, one Alpha Vantage call per pair, plus one per
     * pair that still needs history.
     */
    @PostMapping("/ops/fx/refresh")
    public Map<String, Object> refresh() {
        return Map.of("pairsStored", ingestion.refreshRates(),
                "historyRowsInserted", ingestion.backfillHistory());
    }

    @ExceptionHandler(FxRateNotAvailableException.class)
    public ResponseEntity<Map<String, Object>> noRate(FxRateNotAvailableException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "FX_RATE_NOT_AVAILABLE",
                "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_CONVERSION",
                "message", e.getMessage()));
    }

    public record ConversionView(Money from, Money to, BigDecimal rate, LocalDate rateDate, String source) {
    }
}
