package com.restaurant.pos.expense.spec;

import com.restaurant.pos.expense.domain.Expense;
import com.restaurant.pos.expense.domain.ExpenseStatus;
import com.restaurant.pos.expense.domain.ScopeType;
import com.restaurant.pos.expense.dto.ExpenseSearchCriteria;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Enterprise-grade Specification for dynamic Expense filtering.
 * Centralizes complex query logic for consistency and testability.
 * Now optimized for the dedicated Expense domain entity.
 */
public class ExpenseSpecification {

    public static Specification<Expense> filterBy(ExpenseSearchCriteria criteria, UUID clientId, UUID orgId, boolean canManageAll) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Base Filters
            predicates.add(cb.equal(root.get("clientId"), clientId));
            // Note: OrderType.EXPENSE filter is handled automatically by JPA inheritance (@DiscriminatorValue)

            // Scope handling:
            // ALL    -> no org predicate (global + all branches) for organization admins only.
            // GLOBAL -> only true organization-level expenses where org_id is null.
            // BRANCH -> one branch, either explicit branchId or current branch context.
            String requestedScope = criteria.getScope() != null ? criteria.getScope().trim().toUpperCase() : "";
            if (!canManageAll && orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            } else if (ScopeType.GLOBAL.name().equals(requestedScope)) {
                if (canManageAll) {
                    predicates.add(cb.isNull(root.get("orgId")));
                } else if (orgId != null) {
                    predicates.add(cb.equal(root.get("orgId"), orgId));
                } else {
                    predicates.add(cb.disjunction());
                }
            } else if (ScopeType.BRANCH.name().equals(requestedScope) || criteria.getBranchId() != null) {
                UUID targetOrgId = criteria.getBranchId() != null ? criteria.getBranchId() : orgId;
                if (targetOrgId != null) {
                    predicates.add(cb.equal(root.get("orgId"), targetOrgId));
                } else if (!canManageAll) {
                    predicates.add(cb.disjunction());
                }
            } else if (!canManageAll && orgId == null) {
                predicates.add(cb.disjunction());
            }

            // Date Range
            if (criteria.getFromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("expenseDate"), criteria.getFromDate()));
            }
            if (criteria.getToDate() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("expenseDate"), criteria.getToDate()));
            }

            // Categorization
            if (criteria.getCategoryId() != null) {
                predicates.add(cb.equal(root.get("categoryId"), criteria.getCategoryId()));
            }

            // Payment Method
            if (criteria.getPaymentMethod() != null && !criteria.getPaymentMethod().isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("paymentMethod")), criteria.getPaymentMethod().trim().toUpperCase()));
            }

            // Fuzzy Search (Expense No or Description)
            if (criteria.getSearchTerm() != null && !criteria.getSearchTerm().isBlank()) {
                String safe = criteria.getSearchTerm()
                        .replace("%", "\\%")
                        .replace("_", "\\_")
                        .trim();
                String pattern = "%" + safe.toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("expenseNo")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern)
                ));
            }

            // Status Filtering (Strictly Mutually Exclusive)
            if (criteria.getStatus() != null && ExpenseStatus.VOID.name().equalsIgnoreCase(criteria.getStatus())) {
                // VOID History: Show only records marked as inactive or explicitly voided
                predicates.add(cb.or(
                    cb.equal(root.get("activeFlag"), Expense.INACTIVE_FLAG),
                    cb.equal(root.get("docStatus"), ExpenseStatus.VOID.name())
                ));
            } else {
                // ACTIVE: Show only records that are both isactive='Y' AND not status 'VOID'
                // This is the default if status is null, empty, or "ACTIVE"
                predicates.add(cb.and(
                    cb.equal(root.get("activeFlag"), Expense.ACTIVE_FLAG),
                    cb.notEqual(root.get("docStatus"), ExpenseStatus.VOID.name())
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
