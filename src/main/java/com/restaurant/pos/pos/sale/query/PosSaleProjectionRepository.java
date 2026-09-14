package com.restaurant.pos.pos.sale.query;

import com.restaurant.pos.order.domain.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Dedicated read-only projection repository for POS Sales History.
 * <p>
 * Uses a single native SQL query with LEFT JOINs to fetch all required summary
 * columns in one database roundtrip — completely bypassing the heavyweight JPA
 * entity loading, N+1 customer/order-line hydration, and {@code @Formula}
 * subqueries that plague the existing {@code OrderService.getSalesOrderHistory}.
 * </p>
 * <p>
 * Returns {@link Slice} instead of {@link org.springframework.data.domain.Page}
 * to avoid the expensive {@code COUNT(*)} query on each pagination request.
 * The frontend uses "has more" pagination instead of total-count pagination.
 * </p>
 */
@Repository
public interface PosSaleProjectionRepository extends JpaRepository<Order, UUID> {

    @Query(value = """
        SELECT
            o.id            AS orderId,
            o.order_no      AS orderNo,
            o.order_date    AS orderDate,
            o.order_status  AS orderStatus,
            o.payment_status AS paymentStatus,
            o.fulfillment_type AS fulfillmentType,
            o.table_id      AS tableId,
            o.table_number  AS tableNumber,
            o.customer_id   AS customerId,
            COALESCE(c.name, cc.name, '') AS customerName,
            COALESCE(c.phone, cc.phone, '') AS customerPhone,
            o.is_credit     AS isCredit,
            o.credit_customer_id AS creditCustomerId,
            o.total_amount  AS totalAmount,
            o.total_tax_amount AS totalTaxAmount,
            o.total_discount_amount AS totalDiscountAmount,
            o.grand_total   AS grandTotal,
            o.gross_amount  AS grossAmount,
            p.payment_method AS paymentMethod,
            i.invoice_no    AS invoiceNo,
            i.daily_bill_no AS dailyBillNo,
            p.reference_no  AS paymentNo,
            COALESCE(o.item_count, 0) AS itemCount,
            o.created_at    AS createdAt,
            o.updated_at    AS updatedAt,
            o.created_by    AS createdBy
        FROM orders o
        LEFT JOIN customers c ON c.id = o.customer_id AND c.client_id = o.client_id
        LEFT JOIN credit_customers cc ON cc.id = o.credit_customer_id
        LEFT JOIN LATERAL (
            SELECT payment_method, reference_no
            FROM payments
            WHERE order_id = o.id AND isactive = 'Y'
            ORDER BY created_at DESC LIMIT 1
        ) p ON true
        LEFT JOIN LATERAL (
            SELECT invoice_no, daily_bill_no
            FROM invoices
            WHERE order_id = o.id
            ORDER BY created_at DESC LIMIT 1
        ) i ON true
        WHERE o.client_id = :clientId
          AND o.order_type = 'SALE'
          AND o.isactive = 'Y'
          AND (:orgId IS NULL OR o.org_id = CAST(:orgId AS uuid))
          AND (:terminalId IS NULL OR o.terminal_id = CAST(:terminalId AS uuid))
          AND o.order_date >= :fromDate
          AND o.order_date <= :toDate
          AND (:status IS NULL OR o.order_status = :status)
          AND (
            :search IS NULL
            OR o.order_no ILIKE '%' || :search || '%'
            OR COALESCE(c.name, '') ILIKE '%' || :search || '%'
            OR COALESCE(c.phone, '') ILIKE '%' || :search || '%'
            OR COALESCE(cc.name, '') ILIKE '%' || :search || '%'
            OR COALESCE(cc.phone, '') ILIKE '%' || :search || '%'
          )
        ORDER BY o.order_date DESC, o.created_at DESC
        """, nativeQuery = true)
    Slice<PosSaleSummaryView> findSalesHistorySlice(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("terminalId") UUID terminalId,
            @Param("fromDate") Instant fromDate,
            @Param("toDate") Instant toDate,
            @Param("status") String status,
            @Param("search") String search,
            Pageable pageable);

    /**
     * Live (non-closed) sale orders for the current branch.
     */
    @Query(value = """
        SELECT
            o.id            AS orderId,
            o.order_no      AS orderNo,
            o.order_date    AS orderDate,
            o.order_status  AS orderStatus,
            o.payment_status AS paymentStatus,
            o.fulfillment_type AS fulfillmentType,
            o.table_id      AS tableId,
            o.table_number  AS tableNumber,
            o.customer_id   AS customerId,
            COALESCE(c.name, cc.name, '') AS customerName,
            COALESCE(c.phone, cc.phone, '') AS customerPhone,
            o.is_credit     AS isCredit,
            o.credit_customer_id AS creditCustomerId,
            o.total_amount  AS totalAmount,
            o.total_tax_amount AS totalTaxAmount,
            o.total_discount_amount AS totalDiscountAmount,
            o.grand_total   AS grandTotal,
            o.gross_amount  AS grossAmount,
            NULL            AS paymentMethod,
            NULL            AS invoiceNo,
            NULL            AS dailyBillNo,
            NULL            AS paymentNo,
            COALESCE(o.item_count, 0) AS itemCount,
            o.created_at    AS createdAt,
            o.updated_at    AS updatedAt,
            o.created_by    AS createdBy
        FROM orders o
        LEFT JOIN customers c ON c.id = o.customer_id AND c.client_id = o.client_id
        LEFT JOIN credit_customers cc ON cc.id = o.credit_customer_id
        WHERE o.client_id = :clientId
          AND o.order_type = 'SALE'
          AND o.isactive = 'Y'
          AND (:orgId IS NULL OR o.org_id = CAST(:orgId AS uuid))
          AND o.order_status NOT IN ('COMPLETED', 'CANCELLED', 'VOID')
        ORDER BY o.order_date DESC, o.created_at DESC
        """, nativeQuery = true)
    Slice<PosSaleSummaryView> findLiveSalesOrders(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            Pageable pageable);

    /**
     * Incremental live orders: returns only orders updated after the given timestamp.
     * Streamlined projection without lateral payment/invoice joins for maximum polling throughput.
     */
    @Query(value = """
        SELECT
            o.id            AS orderId,
            o.order_no      AS orderNo,
            o.order_date    AS orderDate,
            o.order_status  AS orderStatus,
            o.payment_status AS paymentStatus,
            o.fulfillment_type AS fulfillmentType,
            o.table_id      AS tableId,
            o.table_number  AS tableNumber,
            o.customer_id   AS customerId,
            COALESCE(c.name, cc.name, '') AS customerName,
            COALESCE(c.phone, cc.phone, '') AS customerPhone,
            o.is_credit     AS isCredit,
            o.credit_customer_id AS creditCustomerId,
            o.total_amount  AS totalAmount,
            o.total_tax_amount AS totalTaxAmount,
            o.total_discount_amount AS totalDiscountAmount,
            o.grand_total   AS grandTotal,
            o.gross_amount  AS grossAmount,
            NULL            AS paymentMethod,
            NULL            AS invoiceNo,
            NULL            AS dailyBillNo,
            NULL            AS paymentNo,
            COALESCE(o.item_count, 0) AS itemCount,
            o.created_at    AS createdAt,
            o.updated_at    AS updatedAt,
            o.created_by    AS createdBy
        FROM orders o
        LEFT JOIN customers c ON c.id = o.customer_id AND c.client_id = o.client_id
        LEFT JOIN credit_customers cc ON cc.id = o.credit_customer_id
        WHERE o.client_id = :clientId
          AND o.order_type = 'SALE'
          AND o.isactive = 'Y'
          AND (:orgId IS NULL OR o.org_id = CAST(:orgId AS uuid))
          AND o.order_status NOT IN ('COMPLETED', 'CANCELLED', 'VOID')
          AND (
              CAST(:cursorTime AS timestamp) IS NULL
              OR o.updated_at > CAST(:cursorTime AS timestamp)
              OR (o.updated_at = CAST(:cursorTime AS timestamp) AND (CAST(:cursorId AS uuid) IS NULL OR o.id > CAST(:cursorId AS uuid)))
          )
        ORDER BY o.updated_at ASC, o.id ASC
        """, nativeQuery = true)
    Slice<PosSaleSummaryView> findLiveSalesOrdersUpdatedAfter(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("cursorTime") java.time.LocalDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * Returns IDs of orders that were in "live" status before the cursor
     * but have since moved to a terminal status (COMPLETED, PAID, CANCELLED, VOID).
     * The frontend uses these to remove orders from its local live-orders state.
     */
    @Query(value = """
        SELECT o.id
        FROM orders o
        WHERE o.client_id = :clientId
          AND o.order_type = 'SALE'
          AND o.isactive = 'Y'
          AND (:orgId IS NULL OR o.org_id = CAST(:orgId AS uuid))
          AND o.order_status IN ('COMPLETED', 'CANCELLED', 'VOID')
          AND (
              CAST(:cursorTime AS timestamp) IS NULL
              OR o.updated_at > CAST(:cursorTime AS timestamp)
              OR (o.updated_at = CAST(:cursorTime AS timestamp) AND (CAST(:cursorId AS uuid) IS NULL OR o.id > CAST(:cursorId AS uuid)))
          )
        ORDER BY o.updated_at ASC, o.id ASC
        LIMIT 200
        """, nativeQuery = true)
    java.util.List<UUID> findRemovedLiveOrderIdsSince(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("cursorTime") java.time.LocalDateTime cursorTime,
            @Param("cursorId") UUID cursorId);

    /**
     * Keyset-paginated product catalog query for POS Standard Mode.
     * Orders by name ASC, id ASC to guarantee deterministic pagination.
     */
    @Query(value = """
        SELECT
            p.id                AS id,
            p.name              AS name,
            p.description       AS description,
            p.price             AS price,
            p.cost_price        AS costPrice,
            p.mrp               AS mrp,
            p.is_available      AS isAvailable,
            p.image_url         AS imageUrl,
            p.category_id       AS categoryId,
            c.name              AS categoryName,
            p.product_code      AS productCode,
            p.barcode           AS barcode,
            p.product_type      AS productType,
            p.tax_rate          AS taxRate,
            p.tax_code          AS taxCode,
            p.is_active         AS isActive,
            p.is_packaged_good  AS isPackagedGood,
            p.is_ingredient     AS isIngredient,
            p.is_variable_price AS isVariablePrice,
            p.is_variant        AS isVariant
        FROM products p
        LEFT JOIN categories c ON c.id = p.category_id
        WHERE (p.client_id = :clientId OR p.client_id IS NULL)
          AND (:orgId IS NULL OR p.org_id = CAST(:orgId AS uuid) OR p.org_id IS NULL)
          AND p.is_active = true
          AND (p.is_ingredient IS FALSE OR p.is_ingredient IS NULL)
          AND (:categoryId IS NULL OR p.category_id = CAST(:categoryId AS uuid))
          AND (
              :search IS NULL
              OR p.name ILIKE '%' || :search || '%'
              OR p.product_code ILIKE '%' || :search || '%'
              OR p.barcode = :search
          )
          AND (
              :cursorName IS NULL
              OR p.name > :cursorName
              OR (p.name = :cursorName AND p.id > CAST(:cursorId AS uuid))
          )
        ORDER BY p.name ASC, p.id ASC
        LIMIT :limit
        """, nativeQuery = true)
    java.util.List<PosProductSummaryView> findProductsKeyset(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("categoryId") UUID categoryId,
            @Param("search") String search,
            @Param("cursorName") String cursorName,
            @Param("cursorId") UUID cursorId,
            @Param("limit") int limit);

    /**
     * Fast, lightweight customer search projection directly bounded by DB limit.
     * Replaces in-memory full customer retrieval.
     */
    @Query(value = """
        SELECT
            c.id AS id,
            c.name AS name,
            c.phone AS phone,
            c.email AS email,
            c.address AS address,
            c.gst_number AS gstNumber,
            c.customer_category AS customerCategory,
            COALESCE(c.loyalty_points, 0) AS loyaltyPoints,
            COALESCE(c.credit_limit, 0) AS creditLimit,
            COALESCE(c.opening_balance, 0) AS balance
        FROM customers c
        WHERE c.client_id = :clientId
          AND c.isactive = 'Y'
          AND (:orgId IS NULL OR c.org_id = CAST(:orgId AS uuid) OR c.org_id IS NULL)
          AND (
              :search IS NULL
              OR c.name ILIKE '%' || :search || '%'
              OR c.phone ILIKE '%' || :search || '%'
          )
        ORDER BY c.name ASC
        LIMIT :limit
        """, nativeQuery = true)
    java.util.List<PosCustomerSummaryView> findCustomersQuickSearch(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("search") String search,
            @Param("limit") int limit);

    /**
     * Fast, lightweight credit customer search projection directly bounded by DB limit.
     */
    @Query(value = """
        SELECT
            cc.id AS id,
            cc.name AS name,
            cc.phone AS phone,
            cc.email AS email,
            NULL AS address,
            NULL AS gstNumber,
            'CREDIT' AS customerCategory,
            0 AS loyaltyPoints,
            COALESCE(cc.credit_limit, 0) AS creditLimit,
            COALESCE(cc.current_balance, cc.opening_balance, 0) AS balance
        FROM credit_customers cc
        WHERE cc.client_id = :clientId
          AND cc.isactive = 'Y'
          AND cc.status = 'ACTIVE'
          AND (
              :search IS NULL
              OR cc.name ILIKE '%' || :search || '%'
              OR cc.phone ILIKE '%' || :search || '%'
          )
        ORDER BY cc.name ASC
        LIMIT :limit
        """, nativeQuery = true)
    java.util.List<PosCustomerSummaryView> findCreditCustomersQuickSearch(
            @Param("clientId") UUID clientId,
            @Param("search") String search,
            @Param("limit") int limit);

}

