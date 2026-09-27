package com.restaurant.pos.order.repository;

import com.restaurant.pos.order.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {
    long countByClientId(UUID clientId);
    List<com.restaurant.pos.order.domain.Payment> findByOrderId(UUID orderId);
    List<com.restaurant.pos.order.domain.Payment> findByOrderIdIn(java.util.Collection<UUID> orderIds);
    List<Payment> findByExpenseId(UUID expenseId);
    default List<Payment> findByClientIdAndOrgIdAndPaymentDateBetweenOrderByPaymentDateAsc(UUID clientId, UUID orgId, LocalDateTime from, LocalDateTime to) {
        return findAll((root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("paymentDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("paymentDate"), to));
            }
            query.orderBy(cb.asc(root.get("paymentDate")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
    }

    default List<Payment> findActivePaymentsInPeriod(
            UUID clientId,
            UUID orgId,
            LocalDateTime from,
            LocalDateTime to
    ) {
        return findAll((root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("clientId"), clientId));
            if (orgId != null) {
                predicates.add(cb.equal(root.get("orgId"), orgId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("paymentDate"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("paymentDate"), to));
            }
            predicates.add(cb.equal(root.get("isactive"), "Y"));
            predicates.add(cb.not(cb.upper(cb.coalesce(root.get("docStatus"), "COMPLETED")).in("VOID", "VOIDED")));
            query.orderBy(cb.asc(root.get("paymentDate")));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });
    }
    @Query("""
            SELECT COUNT(p) > 0 FROM Payment p
            WHERE p.clientId = :clientId
              AND ((:orgId IS NULL AND p.orgId IS NULL) OR p.orgId = :orgId)
              AND p.referenceNo = :referenceNo
            """)
    boolean existsByClientIdAndOrgIdAndReferenceNo(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("referenceNo") String referenceNo);

    java.util.Optional<Payment> findByClientIdAndSourceOperationId(UUID clientId, String sourceOperationId);

    @Query("""
            SELECT COALESCE(SUM(p.amountPaid), 0) FROM Payment p
            WHERE p.clientId = :clientId
              AND p.creditCustomerId = :customerId
              AND (p.isactive IS NULL OR UPPER(p.isactive) != 'N')
              AND (p.docStatus IS NULL OR UPPER(p.docStatus) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
            """)
    java.math.BigDecimal sumPaidByCustomer(@Param("clientId") UUID clientId, @Param("customerId") UUID customerId);

    @Query("""
            SELECT COALESCE(SUM(p.amountPaid), 0) FROM Payment p
            WHERE p.clientId = :clientId
              AND p.paymentType = com.restaurant.pos.order.domain.PaymentType.OUTBOUND
              AND p.creditCustomerId = :vendorId
              AND (p.isactive IS NULL OR UPPER(p.isactive) != 'N')
              AND (p.docStatus IS NULL OR UPPER(p.docStatus) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
            """)
    java.math.BigDecimal sumPaidByVendor(@Param("clientId") UUID clientId, @Param("vendorId") UUID vendorId);

    @Query("""
            SELECT p.creditCustomerId, COALESCE(SUM(p.amountPaid), 0)
            FROM Payment p
            WHERE p.clientId = :clientId
              AND (p.isactive IS NULL OR UPPER(p.isactive) != 'N')
              AND (p.docStatus IS NULL OR UPPER(p.docStatus) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
              AND p.creditCustomerId IN :customerIds
            GROUP BY p.creditCustomerId
            """)
    List<Object[]> sumPaidByCustomerIds(@Param("clientId") UUID clientId, @Param("customerIds") java.util.Collection<UUID> customerIds);

    @Query("""
            SELECT p.creditCustomerId, COALESCE(SUM(p.amountPaid), 0)
            FROM Payment p
            WHERE p.clientId = :clientId
              AND p.paymentType = com.restaurant.pos.order.domain.PaymentType.OUTBOUND
              AND (p.isactive IS NULL OR UPPER(p.isactive) != 'N')
              AND (p.docStatus IS NULL OR UPPER(p.docStatus) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
              AND p.creditCustomerId IN :vendorIds
            GROUP BY p.creditCustomerId
            """)
    List<Object[]> sumPaidByVendorIds(@Param("clientId") UUID clientId, @Param("vendorIds") java.util.Collection<UUID> vendorIds);
}
