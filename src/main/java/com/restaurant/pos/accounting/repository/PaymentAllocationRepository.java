package com.restaurant.pos.accounting.repository;

import com.restaurant.pos.accounting.domain.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, UUID> {

    List<PaymentAllocation> findByClientIdAndOrgIdOrderByAllocationDateDesc(UUID clientId, UUID orgId);

    List<PaymentAllocation> findByPaymentIdAndClientId(UUID paymentId, UUID clientId);

    List<PaymentAllocation> findByInvoiceIdAndClientId(UUID invoiceId, UUID clientId);

    List<PaymentAllocation> findByOrderId(UUID orderId);

    List<PaymentAllocation> findByInvoiceId(UUID invoiceId);

    List<PaymentAllocation> findByOrderIdAndIsactive(UUID orderId, String isactive);
}
