package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.OhlcvBar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface OhlcvBarRepository extends JpaRepository<OhlcvBar, OhlcvBar.Key> {

    /**
     * Main query. The table is partitioned by month and indexed on (ticker, session_date DESC).
     * Filtering on session_date lets Postgres skip partitions (check with EXPLAIN ANALYZE).
     */
    @Query("""
            select b from OhlcvBar b
            where b.ticker = :ticker
              and b.sessionDate >= :from
              and b.sessionDate <= :to
            order by b.sessionDate asc
            """)
    List<OhlcvBar> findSeries(@Param("ticker") String ticker,
                              @Param("from") LocalDate from,
                              @Param("to") LocalDate to);

    List<OhlcvBar> findByTickerOrderBySessionDateDesc(String ticker);

    @Query("select count(b) from OhlcvBar b where b.ticker = :ticker")
    long countForTicker(@Param("ticker") String ticker);

    @Query("select max(b.sessionDate) from OhlcvBar b where b.ticker = :ticker")
    LocalDate findLatestSessionDate(@Param("ticker") String ticker);

    /** High/low per ticker since from. Filters on session_date so partitions are skipped. */
    @Query("""
            select b.ticker as ticker, max(b.high) as high, min(b.low) as low
            from OhlcvBar b
            where b.sessionDate >= :from and b.low > 0
            group by b.ticker
            """)
    List<PriceRange> findRangesSince(@Param("from") LocalDate from);

    interface PriceRange {
        String getTicker();
        java.math.BigDecimal getHigh();
        java.math.BigDecimal getLow();
    }
}
