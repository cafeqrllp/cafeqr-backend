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
    @Query("""
            SELECT p FROM Payment p
            WHERE p.clientId = :clientId
              AND (:orgId IS NULL OR p.orgId = :orgId)
              AND p.paymentDate BETWEEN :from AND :to
            ORDER BY p.paymentDate ASC
            """)
    List<Payment> findByClientIdAndOrgIdAndPaymentDateBetweenOrderByPaymentDateAsc(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    @Query("""
            SELECT p FROM Payment p
            WHERE p.clientId = :clientId
              AND (:orgId IS NULL OR p.orgId = :orgId)
              AND p.paymentDate BETWEEN :from AND :to
              AND p.isactive = 'Y'
              AND UPPER(COALESCE(p.docStatus, 'COMPLETED')) NOT IN ('VOID', 'VOIDED')
            ORDER BY p.paymentDate ASC
            """)
    List<Payment> findActivePaymentsInPeriod(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );
    @Query("""
            SELECT COUNT(p) > 0 FROM Payment p
            WHERE p.clientId = :clientId
              AND ((:orgId IS NULL AND p.orgId IS NULL) OR p.orgId = :orgId)
              AND p.referenceNo = :referenceNo
            """)
    boolean existsByClientIdAndOrgIdAndReferenceNo(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("referenceNo") String referenceNo);
}
