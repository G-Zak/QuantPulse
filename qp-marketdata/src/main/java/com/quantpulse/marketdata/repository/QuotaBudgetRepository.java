package com.quantpulse.marketdata.repository;

import com.quantpulse.marketdata.domain.QuotaBudget;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface QuotaBudgetRepository extends JpaRepository<QuotaBudget, LocalDate> {

    /** Locks today's row so two callers can't both read "1 call left" and both spend it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from QuotaBudget b where b.quotaDate = :date")
    Optional<QuotaBudget> findForUpdate(@Param("date") LocalDate date);
}
