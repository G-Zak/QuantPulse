package com.quantpulse.portfolio.repository;

import com.quantpulse.portfolio.domain.TaxLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TaxLotRepository extends JpaRepository<TaxLot, Long> {

    /** Open lots, oldest first (FIFO order). Uses idx_lot_open. */
    @Query("""
            select l from TaxLot l
            where l.portfolioId = :pid and l.ticker = :ticker and l.remainingQuantity > 0
            order by l.acquiredAt asc, l.id asc
            """)
    List<TaxLot> findOpenLots(@Param("pid") UUID portfolioId, @Param("ticker") String ticker);

    void deleteByPortfolioIdAndTicker(UUID portfolioId, String ticker);
}
