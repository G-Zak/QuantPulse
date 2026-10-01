package com.quantpulse.marketdata.ingestion;

import com.quantpulse.common.money.Money;
import com.quantpulse.marketdata.domain.FxRate;
import com.quantpulse.marketdata.repository.FxRateRepository;
import com.quantpulse.marketdata.upstream.AlphaVantageClient;
import com.quantpulse.marketdata.upstream.AlphaVantageDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps fx_rate up to date.
 *
 * - refreshRates(): one spot rate per pair every day (4 of Alpha Vantage's 25 daily calls).
 * - backfillHistory(): a year of daily rates per pair, so old benchmark prices can be
 *   converted at their own date's rate. Only runs while the history is short, so it
 *   costs nothing once filled.
 *
 * We store both directions (USD/MAD and MAD/USD) since FxConversionService never inverts.
 */
@Service
public class FxRateIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FxRateIngestionService.class);
    private static final String SYNTHETIC = "SYNTHETIC";

    private final AlphaVantageClient client;
    private final FxRateRepository rates;
    private final List<String> pairs;
    private final int historyMinRows;

    public FxRateIngestionService(AlphaVantageClient client,
                                  FxRateRepository rates,
                                  @Value("${quantpulse.alphavantage.fx-pairs:USD-MAD,EUR-MAD,MAD-USD,MAD-EUR}") List<String> pairs,
                                  @Value("${quantpulse.alphavantage.fx-history-min-rows:200}") int historyMinRows) {
        this.client = client;
        this.rates = rates;
        this.pairs = pairs;
        this.historyMinRows = historyMinRows;
    }

    /** @return number of pairs saved */
    @Transactional
    public int refreshRates() {
        int stored = 0;
        for (String pair : pairs) {
            String[] ccy = split(pair);
            Optional<AlphaVantageDtos.FxRateDto> dto = client.fetchFxRate(ccy[0], ccy[1]);
            if (dto.isEmpty()) {
                log.warn("[FX] no rate for {} this run; last stored rate remains in effect", pair);
                continue;
            }
            upsert(dto.get(), client.sourceFor("fx-" + ccy[0] + "-" + ccy[1]));
            stored++;
        }
        log.info("[FX] refreshed {}/{} pairs from {}", stored, pairs.size(), client.sourceName());
        return stored;
    }

    /**
     * Fills daily history for pairs with fewer than historyMinRows rows.
     *
     * Only inserts, except over synthetic rows. A date that already has a real rate keeps it:
     * the spot and the daily close can differ a bit, and changing a rate already used for a
     * conversion would change past results.
     *
     * @return rows inserted
     */
    @Transactional
    public int backfillHistory() {
        int inserted = 0;
        for (String pair : pairs) {
            String[] ccy = split(pair);
            String source = client.sourceFor("fxdaily-" + ccy[0] + "-" + ccy[1]);
            boolean real = !SYNTHETIC.equals(source);
            // Once real data is available, synthetic rows count as missing and get replaced.
            long have = real ? rates.countByBaseCurrencyAndQuoteCurrencyAndSourceNot(ccy[0], ccy[1], SYNTHETIC)
                    : rates.countByBaseCurrencyAndQuoteCurrency(ccy[0], ccy[1]);
            if (have >= historyMinRows) {
                continue;
            }
            List<AlphaVantageDtos.FxDailyPointDto> points = client.fetchFxDaily(ccy[0], ccy[1], true);
            if (points.isEmpty()) {
                log.warn("[FX] no daily history for {} this run", pair);
                continue;
            }
            Set<LocalDate> stored = real ? rates.findNonSyntheticDates(ccy[0], ccy[1])
                    : rates.findStoredDates(ccy[0], ccy[1]);
            List<FxRate> fresh = new ArrayList<>();
            for (AlphaVantageDtos.FxDailyPointDto p : points) {
                if (!stored.contains(p.date())) {
                    fresh.add(new FxRate(ccy[0], ccy[1], p.date(),
                            p.close().setScale(Money.SCALE, Money.ROUNDING), source));
                }
            }
            rates.saveAll(fresh);
            inserted += fresh.size();
            log.info("[FX] {} history: {} new days (had {})", pair, fresh.size(), have);
        }
        return inserted;
    }

    private void upsert(AlphaVantageDtos.FxRateDto dto, String source) {
        // The API gives 8 decimals, we keep 6. Round here, the same way Money does.
        var rate = dto.rate().setScale(Money.SCALE, Money.ROUNDING);
        var key = new FxRate.Key(dto.fromCurrency(), dto.toCurrency(), dto.rateDate());
        rates.findById(key).ifPresentOrElse(
                existing -> existing.refresh(rate, source),
                () -> rates.save(new FxRate(dto.fromCurrency(), dto.toCurrency(), dto.rateDate(),
                        rate, source)));
        log.debug("[FX] {}/{} {} = {}", dto.fromCurrency(), dto.toCurrency(), dto.rateDate(), rate);
    }

    private static String[] split(String pair) {
        return pair.trim().toUpperCase().split("-");
    }
}
