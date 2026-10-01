package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.BenchmarkBar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface BenchmarkBarRepository extends JpaRepository<BenchmarkBar, BenchmarkBar.Key> {

    List<BenchmarkBar> findBySymbolAndSessionDateBetweenOrderBySessionDateAsc(String symbol, LocalDate from, LocalDate to);

    long countBySymbol(String symbol);

    long countBySymbolAndSourceNot(String symbol, String source);

    @Query("select max(b.sessionDate) from BenchmarkBar b where b.symbol = :symbol")
    LocalDate findLatestDate(@Param("symbol") String symbol);

    @Query("select b.sessionDate from BenchmarkBar b where b.symbol = :symbol")
    Set<LocalDate> findStoredDates(@Param("symbol") String symbol);

    @Query("select b.sessionDate from BenchmarkBar b where b.symbol = :symbol and b.source <> 'SYNTHETIC'")
    Set<LocalDate> findNonSyntheticDates(@Param("symbol") String symbol);
}
