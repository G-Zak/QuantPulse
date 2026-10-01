package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.OutboxEvent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Takes a batch of unpublished events for this relay.
     *
     * FOR UPDATE SKIP LOCKED: each relay instance locks different rows and doesn't wait
     * for the others. Ordered by occurredAt, which is good enough, not a strict global order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.occurredAt asc")
    List<OutboxEvent> claimUnpublished(Pageable pageable);

    @Query("select count(e) from OutboxEvent e where e.publishedAt is null")
    long countUnpublished();

    /** Deletes old published events. */
    @Query("delete from OutboxEvent e where e.publishedAt is not null and e.publishedAt < :before")
    @org.springframework.data.jpa.repository.Modifying
    int purgePublishedBefore(@org.springframework.data.repository.query.Param("before") java.time.Instant before);
}
