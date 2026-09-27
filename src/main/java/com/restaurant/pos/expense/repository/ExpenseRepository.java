package com.restaurant.pos.expense.repository;

import com.restaurant.pos.expense.domain.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Enterprise-grade Repository for Expense entities.
 * Dedicated to the expenses table.
 */
@Repository
public interface ExpenseRepository extends JpaRepository<Expense, UUID>, JpaSpecificationExecutor<Expense> {
    /**
     * Retrieves expenses within a specific date range for accounting backfills.
     * 
     * Null Semantics:
     * A null 'orgId' acts as a wildcard (cross-org backfill), returning expenses
     * across all branches (both global orgId=null and branch-specific orgId=UUID).
     */
    default List<Expense> findByClientIdAndOrgIdAndExpenseDateBetweenOrderByExpenseDateAsc(UUID clientId, UUID orgId, Instant from, Instant to) {
        return findAll((root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("expenseDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("expenseDate"), to));
            }
            query.orderBy(cb.asc(root.get("expenseDate")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
    }

    /**
     * Checks if an expense record exists with a given document number.
     * Used by DocumentSequenceService to guarantee sequence number uniqueness.
     * 
     * Null Semantics:
     * A null 'orgId' specifically targets global-scope records (where e.orgId IS NULL),
     * rather than matching branch records. This prevents sequence generation namespace 
     * collisions between global and branch-specific documents.
     */
    @Query("""
            SELECT COUNT(e) > 0 FROM Expense e
            WHERE e.clientId = :clientId
              AND ((:orgId IS NULL AND e.orgId IS NULL) OR e.orgId = :orgId)
              AND e.expenseNo = :expenseNo
            """)
    boolean existsByClientIdAndOrgIdAndExpenseNo(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("expenseNo") String expenseNo);

    @Query("""
            SELECT e FROM Expense e
            WHERE e.clientId = :clientId
              AND ((:orgId IS NULL AND e.orgId IS NULL) OR e.orgId = :orgId)
              AND e.expenseNo = :expenseNo
            """)
    List<Expense> findByClientIdAndOrgIdAndExpenseNo(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("expenseNo") String expenseNo);
}

