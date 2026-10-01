package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.IndexHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface IndexHistoryRepository extends JpaRepository<IndexHistory, IndexHistory.Key> {

    @Query("""
            select h from IndexHistory h
            where h.code = :code and h.sessionDate >= :from and h.sessionDate <= :to
            order by h.sessionDate asc
            """)
    List<IndexHistory> findSeries(@Param("code") String code,
                                  @Param("from") LocalDate from,
                                  @Param("to") LocalDate to);
}
