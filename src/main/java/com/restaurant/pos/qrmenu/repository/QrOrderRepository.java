package com.restaurant.pos.qrmenu.repository;

import com.restaurant.pos.order.domain.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Isolated repository dedicated specifically to the QR Code Scanning & Table Ordering module.
 */
@Repository
public interface QrOrderRepository extends JpaRepository<Order, UUID> {

    @EntityGraph(attributePaths = "lines")
    @Query("""
            SELECT o FROM Order o
            WHERE o.clientId = :clientId
              AND (cast(:orgId as uuid) IS NULL OR o.orgId = :orgId)
              AND ((cast(:tableId as uuid) IS NOT NULL AND o.tableId = :tableId) OR (:tableNumber IS NOT NULL AND o.tableNumber = :tableNumber))
              AND o.isactive = 'Y'
              AND (o.orderStatus IS NULL OR UPPER(o.orderStatus) NOT IN ('COMPLETED', 'CANCELLED', 'VOID', 'PAID', 'SETTLED', 'CLOSED'))
              AND (cast(:since as java.time.LocalDateTime) IS NULL OR o.createdAt >= :since)
            ORDER BY o.createdAt DESC
            """)
    List<Order> findActiveOrdersByTable(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("tableId") UUID tableId,
            @Param("tableNumber") String tableNumber,
            @Param("since") java.time.LocalDateTime since);
}
