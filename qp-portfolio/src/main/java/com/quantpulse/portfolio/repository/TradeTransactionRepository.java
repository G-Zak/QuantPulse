package com.quantpulse.portfolio.repository;

import com.quantpulse.portfolio.domain.TradeTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TradeTransactionRepository extends JpaRepository<TradeTransaction, Long> {

    /** Ledger in replay order. Uses idx_txn_portfolio_ticker. */
    List<TradeTransaction> findByPortfolioIdAndTickerOrderByExecutedAtAscIdAsc(
            UUID portfolioId, String ticker);

    List<TradeTransaction> findByPortfolioIdOrderByExecutedAtDescIdDesc(UUID portfolioId);
}
