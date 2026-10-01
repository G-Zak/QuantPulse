package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.MarketIndex;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketIndexRepository extends JpaRepository<MarketIndex, String> {
}
