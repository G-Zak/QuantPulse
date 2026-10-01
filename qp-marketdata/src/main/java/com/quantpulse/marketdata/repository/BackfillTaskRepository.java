package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.BackfillTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BackfillTaskRepository extends JpaRepository<BackfillTask, Long> {

    @Query("select t from BackfillTask t where t.status = 'PENDING' and t.kind = :kind "
         + "order by t.priority desc, t.id asc")
    List<BackfillTask> findNextPending(@org.springframework.data.repository.query.Param("kind")
                                       BackfillTask.Kind kind, Pageable pageable);

    long countByStatusAndKind(BackfillTask.Status status, BackfillTask.Kind kind);

    long countByStatus(BackfillTask.Status status);

    Optional<BackfillTask> findByTickerAndRangeCodeAndKind(String ticker, String rangeCode,
                                                           BackfillTask.Kind kind);
}
