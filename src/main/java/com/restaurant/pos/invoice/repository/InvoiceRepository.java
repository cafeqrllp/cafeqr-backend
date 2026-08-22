package com.restaurant.pos.invoice.repository;

import com.restaurant.pos.invoice.domain.Invoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.time.LocalDateTime;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {
    Optional<Invoice> findByOrderIdAndClientId(UUID orderId, UUID clientId);
    Optional<Invoice> findByOrderIdAndClientIdAndOrgId(UUID orderId, UUID clientId, UUID orgId);
    List<Invoice> findByOrderId(UUID orderId);
    List<Invoice> findByOrderIdIn(java.util.Collection<UUID> orderIds);
    
    Optional<Invoice> findByIdAndClientId(UUID id, UUID clientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Invoice i WHERE i.id = :id AND i.clientId = :clientId")
    Optional<Invoice> findByIdAndClientIdForUpdate(@Param("id") UUID id, @Param("clientId") UUID clientId);
    Optional<Invoice> findByIdAndClientIdAndOrgId(UUID id, UUID clientId, UUID orgId);
    List<Invoice> findByExpenseId(UUID expenseId);
    
    Optional<Invoice> findByInvoiceNoAndClientId(String invoiceNo, UUID clientId);
    Optional<Invoice> findByInvoiceNoAndClientIdAndOrgId(String invoiceNo, UUID clientId, UUID orgId);

    @Query("""
            SELECT i FROM Invoice i
            WHERE i.clientId = :clientId
              AND (:orgId IS NULL OR i.orgId = :orgId)
              AND i.invoiceDate BETWEEN :from AND :to
            ORDER BY i.invoiceDate ASC
            """)
    List<Invoice> findByClientIdAndOrgIdAndInvoiceDateBetweenOrderByInvoiceDateAsc(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("""
            SELECT COALESCE(MAX(i.dailyBillNo), 0) FROM Invoice i
            WHERE i.clientId = :clientId
              AND (:orgId IS NULL OR i.orgId = :orgId)
              AND i.invoiceDate BETWEEN :start AND :end
            """)
    int findMaxDailyBillNo(
        @Param("clientId") UUID clientId,
        @Param("orgId") UUID orgId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );

    @Query("""
            SELECT COUNT(i) > 0 FROM Invoice i
            WHERE i.clientId = :clientId
              AND ((:orgId IS NULL AND i.orgId IS NULL) OR i.orgId = :orgId)
              AND i.invoiceNo = :invoiceNo
            """)
    boolean existsByClientIdAndOrgIdAndInvoiceNo(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("invoiceNo") String invoiceNo);
    
    long countByClientId(UUID clientId);

    @Query("""
            SELECT i FROM Invoice i
            WHERE i.orderId = :orderId
              AND i.clientId = :clientId
              AND i.status != 'VOID'
              AND i.isactive = 'Y'
            """)
    Optional<Invoice> findActiveByOrderIdAndClientId(@Param("orderId") UUID orderId, @Param("clientId") UUID clientId);

    @Query("""
            SELECT i FROM Invoice i
            WHERE i.orderId = :orderId
              AND i.clientId = :clientId
              AND (:orgId IS NULL OR i.orgId = :orgId)
              AND i.status != 'VOID'
              AND i.isactive = 'Y'
            """)
    Optional<Invoice> findActiveByOrderIdAndClientIdAndOrgId(@Param("orderId") UUID orderId, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("""
            SELECT i FROM Invoice i
            WHERE i.invoiceNo = :invoiceNo
              AND i.clientId = :clientId
              AND i.status != 'VOID'
              AND i.isactive = 'Y'
            """)
    Optional<Invoice> findActiveByInvoiceNoAndClientId(@Param("invoiceNo") String invoiceNo, @Param("clientId") UUID clientId);

    @Query("""
            SELECT i FROM Invoice i
            WHERE i.invoiceNo = :invoiceNo
              AND i.clientId = :clientId
              AND (:orgId IS NULL OR i.orgId = :orgId)
              AND i.status != 'VOID'
              AND i.isactive = 'Y'
            """)
    Optional<Invoice> findActiveByInvoiceNoAndClientIdAndOrgId(@Param("invoiceNo") String invoiceNo, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId);

    @Query("""
            SELECT COALESCE(SUM(i.totalAmount), 0) FROM Invoice i
            WHERE i.clientId = :clientId
              AND i.creditCustomerId = :customerId
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.CUSTOMER_INVOICE
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
            """)
    java.math.BigDecimal sumTotalAmountByCustomer(@Param("clientId") UUID clientId, @Param("customerId") UUID customerId);

    @Query("""
            SELECT COALESCE(SUM(i.amountDue), 0) FROM Invoice i
            WHERE i.clientId = :clientId
              AND i.creditCustomerId = :customerId
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.CUSTOMER_INVOICE
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
            """)
    java.math.BigDecimal sumAmountDueByCustomer(@Param("clientId") UUID clientId, @Param("customerId") UUID customerId);

    @Query("""
            SELECT COUNT(i) FROM Invoice i
            WHERE i.clientId = :clientId
              AND i.creditCustomerId = :customerId
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.CUSTOMER_INVOICE
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
              AND i.amountDue > 0
            """)
    long countOpenInvoicesByCustomer(@Param("clientId") UUID clientId, @Param("customerId") UUID customerId);

    @Query("""
            SELECT COALESCE(SUM(i.totalAmount), 0) FROM Invoice i
            WHERE i.clientId = :clientId
              AND (i.vendorId = :vendorId OR i.creditCustomerId = :vendorId)
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.VENDOR_BILL
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
            """)
    java.math.BigDecimal sumTotalAmountByVendor(@Param("clientId") UUID clientId, @Param("vendorId") UUID vendorId);

    @Query("""
            SELECT COALESCE(SUM(i.amountDue), 0) FROM Invoice i
            WHERE i.clientId = :clientId
              AND (i.vendorId = :vendorId OR i.creditCustomerId = :vendorId)
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.VENDOR_BILL
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
            """)
    java.math.BigDecimal sumAmountDueByVendor(@Param("clientId") UUID clientId, @Param("vendorId") UUID vendorId);

    @Query("""
            SELECT COUNT(i) FROM Invoice i
            WHERE i.clientId = :clientId
              AND (i.vendorId = :vendorId OR i.creditCustomerId = :vendorId)
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.VENDOR_BILL
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
              AND i.amountDue > 0
            """)
    long countOpenInvoicesByVendor(@Param("clientId") UUID clientId, @Param("vendorId") UUID vendorId);

    @Query("""
            SELECT i.creditCustomerId, COALESCE(SUM(i.totalAmount), 0)
            FROM Invoice i
            WHERE i.clientId = :clientId
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.CUSTOMER_INVOICE
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
              AND i.creditCustomerId IN :customerIds
            GROUP BY i.creditCustomerId
            """)
    List<Object[]> sumTotalAmountByCustomerIds(@Param("clientId") UUID clientId, @Param("customerIds") java.util.Collection<UUID> customerIds);

    @Query("""
            SELECT i.creditCustomerId, COALESCE(SUM(i.amountDue), 0)
            FROM Invoice i
            WHERE i.clientId = :clientId
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.CUSTOMER_INVOICE
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
              AND i.creditCustomerId IN :customerIds
            GROUP BY i.creditCustomerId
            """)
    List<Object[]> sumAmountDueByCustomerIds(@Param("clientId") UUID clientId, @Param("customerIds") java.util.Collection<UUID> customerIds);

    @Query("""
            SELECT i.creditCustomerId, COUNT(i)
            FROM Invoice i
            WHERE i.clientId = :clientId
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.CUSTOMER_INVOICE
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
              AND i.amountDue > 0
              AND i.creditCustomerId IN :customerIds
            GROUP BY i.creditCustomerId
            """)
    List<Object[]> countOpenInvoicesByCustomerIds(@Param("clientId") UUID clientId, @Param("customerIds") java.util.Collection<UUID> customerIds);

    @Query("""
            SELECT COALESCE(i.vendorId, i.creditCustomerId), COALESCE(SUM(i.totalAmount), 0)
            FROM Invoice i
            WHERE i.clientId = :clientId
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.VENDOR_BILL
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
              AND (i.vendorId IN :vendorIds OR i.creditCustomerId IN :vendorIds)
            GROUP BY COALESCE(i.vendorId, i.creditCustomerId)
            """)
    List<Object[]> sumTotalAmountByVendorIds(@Param("clientId") UUID clientId, @Param("vendorIds") java.util.Collection<UUID> vendorIds);

    @Query("""
            SELECT COALESCE(i.vendorId, i.creditCustomerId), COALESCE(SUM(i.amountDue), 0)
            FROM Invoice i
            WHERE i.clientId = :clientId
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.VENDOR_BILL
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
              AND (i.vendorId IN :vendorIds OR i.creditCustomerId IN :vendorIds)
            GROUP BY COALESCE(i.vendorId, i.creditCustomerId)
            """)
    List<Object[]> sumAmountDueByVendorIds(@Param("clientId") UUID clientId, @Param("vendorIds") java.util.Collection<UUID> vendorIds);

    @Query("""
            SELECT COALESCE(i.vendorId, i.creditCustomerId), COUNT(i)
            FROM Invoice i
            WHERE i.clientId = :clientId
              AND i.invoiceType = com.restaurant.pos.invoice.domain.InvoiceType.VENDOR_BILL
              AND (i.isactive IS NULL OR UPPER(i.isactive) != 'N')
              AND (i.status IS NULL OR UPPER(i.status) NOT IN ('VOID', 'VOIDED', 'CANCELLED', 'INACTIVE'))
              AND i.amountDue > 0
              AND (i.vendorId IN :vendorIds OR i.creditCustomerId IN :vendorIds)
            GROUP BY COALESCE(i.vendorId, i.creditCustomerId)
            """)
    List<Object[]> countOpenInvoicesByVendorIds(@Param("clientId") UUID clientId, @Param("vendorIds") java.util.Collection<UUID> vendorIds);
}
