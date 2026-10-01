package com.quantpulse.portfolio.repository;

import com.quantpulse.portfolio.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "delete from ProcessedEvent e where e.processedAt < :before")
    int purgeBefore(@org.springframework.data.repository.query.Param("before") Instant before);
}
