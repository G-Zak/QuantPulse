package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.FxRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface FxRateRepository extends JpaRepository<FxRate, FxRate.Key> {

    /**
     * Latest rate for a pair on or before asOf.
     * On or before, because there's no rate on Saturday and Friday's is fine.
     */
    Optional<FxRate> findFirstByBaseCurrencyAndQuoteCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(
            String baseCurrency, String quoteCurrency, LocalDate asOf);

    /** Latest and previous rate, for the daily change. */
    List<FxRate> findTop2ByBaseCurrencyAndQuoteCurrencyOrderByRateDateDesc(String baseCurrency, String quoteCurrency);

    List<FxRate> findByBaseCurrencyAndQuoteCurrencyAndRateDateBetweenOrderByRateDateAsc(
            String baseCurrency, String quoteCurrency, LocalDate from, LocalDate to);

    long countByBaseCurrencyAndQuoteCurrency(String baseCurrency, String quoteCurrency);

    long countByBaseCurrencyAndQuoteCurrencyAndSourceNot(String baseCurrency, String quoteCurrency, String source);

    @Query("select r.rateDate from FxRate r where r.baseCurrency = :base and r.quoteCurrency = :quote")
    Set<LocalDate> findStoredDates(@Param("base") String baseCurrency, @Param("quote") String quoteCurrency);

    @Query("select r.rateDate from FxRate r where r.baseCurrency = :base and r.quoteCurrency = :quote and r.source <> 'SYNTHETIC'")
    Set<LocalDate> findNonSyntheticDates(@Param("base") String baseCurrency, @Param("quote") String quoteCurrency);
}
