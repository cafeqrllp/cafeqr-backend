package com.restaurant.pos.order.spec;

import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.dto.OrderSearchCriteria;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class OrderSpecification {

    public static Specification<Order> filterBy(OrderSearchCriteria criteria, UUID clientId, UUID orgId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Base Filters
            predicates.add(cb.equal(root.get("clientId"), clientId));

            if (criteria.getOrderType() != null) {
                predicates.add(cb.equal(root.get("orderType"), criteria.getOrderType()));
            }

            // Multi-Branch Security Logic
            if (!SecurityUtils.isSuperAdmin() && orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            } else if (criteria.getBranchId() != null) {
                predicates.add(cb.equal(root.get("orgId"), criteria.getBranchId()));
            }

            // Date Range
            if (criteria.getFromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("orderDate"), criteria.getFromDate()));
            }
            if (criteria.getToDate() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("orderDate"), criteria.getToDate()));
            }

            // Parties
            if (criteria.getVendorId() != null) {
                predicates.add(cb.equal(root.get("vendorId"), criteria.getVendorId()));
            }
            if (criteria.getCustomerId() != null) {
                predicates.add(cb.equal(root.get("customerId"), criteria.getCustomerId()));
            }

            // Warehouse
            if (criteria.getWarehouseId() != null) {
                predicates.add(cb.equal(root.get("warehouseId"), criteria.getWarehouseId()));
            }

            // Payment Method
            if (criteria.getTerminalId() != null) {
                predicates.add(cb.equal(root.get("terminalId"), criteria.getTerminalId()));
            }
            if (criteria.getPaymentMethod() != null && !criteria.getPaymentMethod().isBlank()) {
                predicates.add(cb.equal(root.get("paymentMethod"), criteria.getPaymentMethod()));
            }

            // Fuzzy Search
            if (criteria.getSearchTerm() != null && !criteria.getSearchTerm().isBlank()) {
                String safe = criteria.getSearchTerm()
                        .replace("%", "\\%")
                        .replace("_", "\\_")
                        .trim();
                String pattern = "%" + safe.toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(root.get("orderNo")), pattern),
                    cb.like(cb.lower(root.get("description")), pattern),
                    cb.like(cb.lower(root.get("remarks")), pattern)
                ));
            }

            // Status Filtering
            String status = criteria.getStatus();
            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
                if ("CONFIRMED_COMPLETED".equalsIgnoreCase(status) || "COMPLETED_AND_RECEIVED".equalsIgnoreCase(status)) {
                    predicates.add(cb.or(
                        cb.equal(root.get("orderStatus"), "CONFIRMED"),
                        cb.equal(root.get("orderStatus"), "COMPLETED")
                    ));
                    predicates.add(cb.equal(root.get("isactive"), "Y"));
                    predicates.add(cb.equal(cb.locate(root.get("orderNo"), "_VOID_"), 0));
                } else if ("VOID".equalsIgnoreCase(status)) {
                    predicates.add(cb.or(
                        cb.equal(root.get("isactive"), "N"),
                        cb.equal(root.get("orderStatus"), "VOID")
                    ));
                } else {
                    predicates.add(cb.equal(root.get("orderStatus"), status));
                    predicates.add(cb.equal(root.get("isactive"), "Y"));
                    predicates.add(cb.equal(cb.locate(root.get("orderNo"), "_VOID_"), 0));
                }
            } else {
                // Default: show active non-void records
                predicates.add(cb.equal(root.get("isactive"), "Y"));
                predicates.add(cb.notEqual(root.get("orderStatus"), "VOID"));
                predicates.add(cb.equal(cb.locate(root.get("orderNo"), "_VOID_"), 0));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
