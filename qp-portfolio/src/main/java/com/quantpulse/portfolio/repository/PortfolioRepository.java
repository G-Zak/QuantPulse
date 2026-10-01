package com.quantpulse.portfolio.repository;

import com.quantpulse.portfolio.domain.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {
    List<Portfolio> findByOwner(String owner);
    Optional<Portfolio> findByOwnerAndName(String owner, String name);
}
