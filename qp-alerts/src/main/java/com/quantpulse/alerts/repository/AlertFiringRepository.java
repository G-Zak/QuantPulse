package com.quantpulse.alerts.repository;

import com.quantpulse.alerts.domain.AlertFiring;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlertFiringRepository extends JpaRepository<AlertFiring, Long> {
    List<AlertFiring> findByRuleIdOrderByFiredAtDesc(UUID ruleId);
    List<AlertFiring> findAllByOrderByFiredAtDesc(Pageable pageable);
}
