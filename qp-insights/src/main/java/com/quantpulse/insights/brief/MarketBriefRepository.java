package com.quantpulse.insights.brief;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface MarketBriefRepository extends JpaRepository<MarketBrief, Long> {

    Optional<MarketBrief> findFirstByOrderByGeneratedAtDesc();

    boolean existsByBriefDate(LocalDate briefDate);
}
