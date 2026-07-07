package com.restaurant.pos.order.repository;

import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {
    @EntityGraph(attributePaths = "lines")
    List<Order> findByClientIdOrderByCreatedAtDesc(UUID clientId);

    @EntityGraph(attributePaths = "lines")
    List<Order> findByClientIdAndOrgIdOrderByCreatedAtDesc(UUID clientId, UUID orgId);

    // Use OrderType enum — Spring Data JPA handles @Enumerated(STRING) automatically
    @EntityGraph(attributePaths = "lines")
    List<Order> findByClientIdAndOrderTypeOrderByCreatedAtDesc(UUID clientId, OrderType orderType);

    org.springframework.data.domain.Page<Order> findByClientIdAndOrderTypeOrderByCreatedAtDesc(UUID clientId, OrderType orderType, org.springframework.data.domain.Pageable pageable);

    @EntityGraph(attributePaths = "lines")
    List<Order> findByClientIdAndOrgIdAndOrderTypeOrderByCreatedAtDesc(UUID clientId, UUID orgId, OrderType orderType);

    org.springframework.data.domain.Page<Order> findByClientIdAndOrgIdAndOrderTypeOrderByCreatedAtDesc(UUID clientId, UUID orgId, OrderType orderType, org.springframework.data.domain.Pageable pageable);

    @EntityGraph(attributePaths = "lines")
    @Query("""
            SELECT o FROM Order o
            WHERE o.clientId = :clientId
              AND (:orgId IS NULL OR o.orgId = :orgId)
              AND o.orderType = :orderType
              AND o.isactive = 'Y'
              AND (o.orderStatus IS NULL OR UPPER(o.orderStatus) NOT IN :closedStatuses)
            ORDER BY o.orderDate DESC, o.createdAt DESC
            """)
    List<Order> findLiveOrders(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("orderType") OrderType orderType, @Param("closedStatuses") Collection<String> closedStatuses);

    @Query("""
            SELECT o FROM Order o
            WHERE o.clientId = :clientId
              AND (:orgId IS NULL OR o.orgId = :orgId)
              AND o.orderType = :orderType
              AND o.isactive = 'Y'
              AND o.updatedAt >= :updatedAfter
            ORDER BY o.updatedAt DESC, o.orderDate DESC
            """)
    Slice<Order> findChangedOrders(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("orderType") OrderType orderType, @Param("updatedAfter") LocalDateTime updatedAfter, Pageable pageable);

    @EntityGraph(attributePaths = "lines")
    List<Order> findByClientIdAndOrderStatusInOrderByCreatedAtDesc(UUID clientId, List<String> statuses);

    @EntityGraph(attributePaths = "lines")
    List<Order> findByClientIdAndOrgIdAndOrderStatusInOrderByCreatedAtDesc(UUID clientId, UUID orgId, List<String> statuses);

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findByIdAndClientId(UUID id, UUID clientId);

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findByIdAndClientIdAndOrgId(UUID id, UUID clientId, UUID orgId);

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findByOrderNoAndClientId(String orderNo, UUID clientId);

    @EntityGraph(attributePaths = "lines")
    @Query("""
            SELECT o FROM Order o
            WHERE o.clientId = :clientId
              AND (:orgId IS NULL OR o.orgId = :orgId)
              AND o.orderDate BETWEEN :from AND :to
            ORDER BY o.orderDate ASC
            """)
    List<Order> findByClientIdAndOrgIdAndOrderDateBetweenOrderByOrderDateAsc(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("from") java.time.Instant from, @Param("to") java.time.Instant to);

    @Query("""
            SELECT COUNT(o) > 0 FROM Order o
            WHERE o.clientId = :clientId
              AND ((:orgId IS NULL AND o.orgId IS NULL) OR o.orgId = :orgId)
              AND o.orderNo = :orderNo
            """)
    boolean existsByClientIdAndOrgIdAndOrderNo(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("orderNo") String orderNo);

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findBySourceOperationIdAndClientId(String sourceOperationId, UUID clientId);

    @EntityGraph(attributePaths = "lines")
    Optional<Order> findByClientIdAndOrgIdAndSourceLocalRefAndOrderStatusNot(UUID clientId, UUID orgId, String sourceLocalRef, String orderStatus);

    org.springframework.data.domain.Page<Order> findByClientId(UUID clientId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Order> findByClientIdAndOrgId(UUID clientId, UUID orgId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Order> findByClientIdAndOrderStatusIn(UUID clientId, List<String> statuses, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Order> findByClientIdAndOrgIdAndOrderStatusIn(UUID clientId, UUID orgId, List<String> statuses, org.springframework.data.domain.Pageable pageable);

    @EntityGraph(attributePaths = "lines")
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithLines(@Param("id") UUID id);

    long countByClientId(UUID clientId);

    /**
     * Fetches all revisions of an order (current + all VOID predecessors) by matching
     * on the base order number. VOID records are stored as "ORD-001_VOID_0", "ORD-001_VOID_1" etc.,
     * so we match on orderNo = :orderNo OR orderNo LIKE :orderNo_VOID_%.
     */
    @EntityGraph(attributePaths = "lines")
    @Query("""
            SELECT o FROM Order o
            WHERE o.clientId = :clientId
              AND (o.orderNo = :orderNo OR o.orderNo LIKE :voidPrefix)
            ORDER BY o.revisionNumber ASC NULLS FIRST, o.createdAt ASC
            """)
    List<Order> findAllRevisionsByOrderNo(
            @Param("clientId") UUID clientId,
            @Param("orderNo") String orderNo,
            @Param("voidPrefix") String voidPrefix);

    @Query("""
            SELECT o FROM Order o
            WHERE o.clientId = :clientId
              AND (:orgId IS NULL OR o.orgId = :orgId)
              AND o.orderNo = :orderNo
              AND o.isactive = 'Y'
              AND o.orderStatus != 'VOID'
            """)
    Optional<Order> findActiveByOrderNoAndClientIdAndOrgId(
            @Param("orderNo") String orderNo,
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId);

    @Query("""
            SELECT o FROM Order o
            WHERE o.clientId = :clientId
              AND o.orderNo = :orderNo
              AND o.isactive = 'Y'
              AND o.orderStatus != 'VOID'
            """)
    Optional<Order> findActiveByOrderNoAndClientId(
            @Param("orderNo") String orderNo,
            @Param("clientId") UUID clientId);
}
