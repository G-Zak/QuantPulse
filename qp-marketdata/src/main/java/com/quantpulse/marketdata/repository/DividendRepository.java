package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.Dividend;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DividendRepository extends JpaRepository<Dividend, Long> {

    List<Dividend> findByTickerOrderByExDateDesc(String ticker);

    Optional<Dividend> findByTickerAndExDate(String ticker, LocalDate exDate);

    /**
     * Dividends per share over the last 12 months.
     * Summing the real 12 months works even when a company pays twice or skips a year.
     */
    @Query("""
            select coalesce(sum(d.amount), 0) from Dividend d
            where d.ticker = :ticker and d.exDate >= :since
            """)
    java.math.BigDecimal sumSince(@Param("ticker") String ticker, @Param("since") LocalDate since);

    @Query("select distinct d.ticker from Dividend d")
    List<String> findTickersWithDividends();
}
