package com.restaurant.pos.purchase.query;

import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reusable Specification builder for Purchase Order queries.
 */
public class PurchaseOrderSpecifications {

    public static Specification<Order> withFilters(PurchaseOrderSearchRequest request, UUID clientId, UUID orgId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            predicates.add(cb.equal(root.get("orderType"), OrderType.PURCHASE));

            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (request.getVendorId() != null) {
                predicates.add(cb.equal(root.get("vendorId"), request.getVendorId()));
            }
            if (request.getWarehouseId() != null) {
                predicates.add(cb.equal(root.get("warehouseId"), request.getWarehouseId()));
            }
            String effectiveStatus = StringUtils.hasText(request.getStatusRaw()) 
                    ? request.getStatusRaw().trim().toUpperCase() 
                    : (request.getStatus() != null ? request.getStatus().name() : null);

            if (StringUtils.hasText(effectiveStatus)) {
                if ("CONFIRMED_COMPLETED".equals(effectiveStatus) || "COMPLETED_AND_RECEIVED".equals(effectiveStatus)) {
                    predicates.add(cb.or(
                            cb.equal(cb.upper(root.get("orderStatus")), "CONFIRMED"),
                            cb.equal(cb.upper(root.get("orderStatus")), "COMPLETED")
                    ));
                } else if ("VOID".equals(effectiveStatus) || "CANCELLED".equals(effectiveStatus)) {
                    predicates.add(cb.or(
                            cb.equal(cb.upper(root.get("orderStatus")), "VOID"),
                            cb.equal(cb.upper(root.get("orderStatus")), "CANCELLED")
                    ));
                } else {
                    predicates.add(cb.equal(cb.upper(root.get("orderStatus")), effectiveStatus));
                }
            }
            if (StringUtils.hasText(request.getPaymentMethod())) {
                String targetMethod = request.getPaymentMethod().trim().toUpperCase();

                if ("CREDIT".equals(targetMethod)) {
                    // For Credit orders: Check paymentMethod formula, order paymentStatus PENDING, or linked invoice with isCredit / UNPAID
                    jakarta.persistence.criteria.Subquery<UUID> invoiceCreditSubquery = query.subquery(UUID.class);
                    jakarta.persistence.criteria.Root<com.restaurant.pos.invoice.domain.Invoice> invRoot = invoiceCreditSubquery.from(com.restaurant.pos.invoice.domain.Invoice.class);
                    invoiceCreditSubquery.select(invRoot.get("orderId"))
                            .where(
                                    cb.equal(invRoot.get("orderId"), root.get("id")),
                                    cb.or(
                                            cb.isTrue(invRoot.get("isCredit")),
                                            cb.equal(cb.upper(invRoot.get("status")), "UNPAID")
                                    )
                            );

                    Predicate matchFormulaCredit = cb.equal(cb.upper(root.get("paymentMethod")), "CREDIT");
                    Predicate matchPendingStatus = cb.equal(cb.upper(root.get("paymentStatus")), "PENDING");
                    Predicate matchInvoiceCredit = cb.exists(invoiceCreditSubquery);

                    predicates.add(cb.or(matchFormulaCredit, matchPendingStatus, matchInvoiceCredit));
                } else {
                    // For general payment types: Check direct paymentMethod formula on payments, OR payment splits
                    jakarta.persistence.criteria.Subquery<UUID> splitSubquery = query.subquery(UUID.class);
                    jakarta.persistence.criteria.Root<com.restaurant.pos.order.domain.Payment> payRoot = splitSubquery.from(com.restaurant.pos.order.domain.Payment.class);
                    jakarta.persistence.criteria.Root<com.restaurant.pos.order.domain.PaymentSplit> splitRoot = splitSubquery.from(com.restaurant.pos.order.domain.PaymentSplit.class);
                    splitSubquery.select(payRoot.get("orderId"))
                            .where(
                                    cb.equal(payRoot.get("orderId"), root.get("id")),
                                    cb.equal(splitRoot.get("paymentId"), payRoot.get("id")),
                                    cb.equal(cb.upper(splitRoot.get("paymentMethod")), targetMethod)
                            );

                    Predicate matchDirectMethod = cb.equal(cb.upper(root.get("paymentMethod")), targetMethod);
                    Predicate matchSplitMethod = cb.exists(splitSubquery);

                    predicates.add(cb.or(matchDirectMethod, matchSplitMethod));
                }
            }
            jakarta.persistence.criteria.Expression<java.time.Instant> effectiveDate = cb.coalesce(root.get("orderDate"), root.get("createdAt"));
            if (request.getFromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(effectiveDate, request.getFromDate()));
            }
            if (request.getToDate() != null) {
                predicates.add(cb.lessThanOrEqualTo(effectiveDate, request.getToDate()));
            }
            if (StringUtils.hasText(request.getSearchTerm())) {
                String pattern = "%" + request.getSearchTerm().trim().toLowerCase() + "%";
                Predicate matchOrderNo = cb.like(cb.lower(root.get("orderNo")), pattern);
                Predicate matchRef = cb.like(cb.lower(root.get("reference")), pattern);
                predicates.add(cb.or(matchOrderNo, matchRef));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
