package com.quantpulse.portfolio.repository;

import com.quantpulse.portfolio.domain.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PositionRepository extends JpaRepository<Position, Long> {

    Optional<Position> findByPortfolioIdAndTicker(UUID portfolioId, String ticker);

    List<Position> findByPortfolioId(UUID portfolioId);

    @Query("select p from Position p where p.portfolioId = :pid and p.quantity > 0")
    List<Position> findOpenPositions(@Param("pid") UUID portfolioId);

    /** All positions in this ticker, across portfolios. One price event updates all of them. */
    List<Position> findByTicker(String ticker);
}
