package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.AlphaVantageQuotaBudget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface AlphaVantageQuotaBudgetRepository extends JpaRepository<AlphaVantageQuotaBudget, LocalDate> {

    /** Row lock, same reason as QuotaBudgetRepository.findForUpdate. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AlphaVantageQuotaBudget b where b.quotaDate = :date")
    Optional<AlphaVantageQuotaBudget> findForUpdate(@Param("date") LocalDate date);
}
