package com.quantpulse.alerts.repository;

import com.quantpulse.alerts.domain.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {

    /** All enabled rules for a ticker. Uses idx_alert_rule_ticker. */
    List<AlertRule> findByTickerAndEnabledTrue(String ticker);

    List<AlertRule> findByOwner(String owner);
}
