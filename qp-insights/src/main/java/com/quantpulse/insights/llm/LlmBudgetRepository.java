package com.quantpulse.insights.llm;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface LlmBudgetRepository extends JpaRepository<LlmBudget, LocalDate> {

    /** Row lock, so two regenerate clicks can't both use the last call. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from LlmBudget b where b.budgetDate = :date")
    Optional<LlmBudget> findForUpdate(@Param("date") LocalDate date);
}
