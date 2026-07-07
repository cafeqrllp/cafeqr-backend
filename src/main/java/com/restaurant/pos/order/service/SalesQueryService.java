package com.restaurant.pos.order.service;

import com.restaurant.pos.client.repository.TerminalRepository;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.order.dto.OrderLineSummaryDto;
import com.restaurant.pos.order.dto.OrderSummaryDto;
import com.restaurant.pos.order.domain.OrderType;
import com.restaurant.pos.order.dto.report.PageResponse;
import com.restaurant.pos.order.dto.report.SalesDashboardQuery;
import com.restaurant.pos.order.dto.report.SalesDashboardResponse;
import com.restaurant.pos.order.dto.report.SalesSummaryDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesQueryService {

    private static final Duration MAX_REPORT_RANGE = Duration.ofDays(31);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TerminalRepository terminalRepository;

    @Transactional(readOnly = true, timeout = 10)
    public SalesDashboardResponse getDashboard(SalesDashboardQuery query) {
        validateQueryRange(query.getFrom(), query.getTo());

        UUID orgId;
        if (SecurityUtils.isSuperAdmin()) {
            orgId = query.getOrgId();
        } else {
            orgId = TenantContext.getCurrentOrg();
        }

        UUID clientId = TenantContext.getCurrentTenant();
        if (query.getTerminalId() != null) {
            if (orgId != null) {
                terminalRepository.findByIdAndClientIdAndOrgId(query.getTerminalId(), clientId, orgId)
                        .orElseThrow(() -> new BusinessException("Terminal not found or does not belong to the active branch"));
            } else {
                terminalRepository.findByIdAndClientId(query.getTerminalId(), clientId)
                        .orElseThrow(() -> new BusinessException("Terminal not found"));
            }
        }

        // Build shared criteria parameters with normalized case
        String status = query.getStatus() == null
                ? null
                : query.getStatus().trim().toUpperCase(Locale.ROOT);

        SalesQueryCriteria criteria = new SalesQueryCriteria(
                clientId,
                orgId,
                query.getTerminalId(),
                query.getFrom(),
                query.getTo(),
                query.getQ() == null ? null : query.getQ().trim(),
                status
        );

        MapSqlParameterSource params = new MapSqlParameterSource();
        String whereClause = buildWhereClause(criteria, params);

        // 1. Fetch aggregated sales summary statistics
        SalesSummaryDto summary = fetchSalesSummary(whereClause, params);

        // 2. Fetch the requested page of orders
        int page = query.getEffectivePage();
        int size = query.getEffectiveSize();

        long totalElements = fetchCount(whereClause, params);
        List<OrderSummaryDto> ordersContent = fetchOrdersList(whereClause, params, page, size);
        int totalPages = totalElements == 0 ? 0 : Math.toIntExact(1 + (totalElements - 1) / size);

        boolean hasNext = page < totalPages - 1;
        boolean hasPrevious = page > 0;

        PageResponse<OrderSummaryDto> ordersPage = PageResponse.<OrderSummaryDto>builder()
                .content(ordersContent)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .hasNext(hasNext)
                .hasPrevious(hasPrevious)
                .build();

        return SalesDashboardResponse.builder()
                .summary(summary)
                .orders(ordersPage)
                .build();
    }

    private void validateQueryRange(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Query date parameters cannot be null");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("Query from date must be before to date");
        }
        if (Duration.between(from, to).compareTo(MAX_REPORT_RANGE) > 0) {
            throw new IllegalArgumentException("Query date range cannot exceed 31 days");
        }
    }

    private String buildWhereClause(SalesQueryCriteria criteria, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder("WHERE o.client_id = :clientId AND o.order_type = 'SALE' ");
        params.addValue("clientId", criteria.clientId());
        params.addValue("orgId", criteria.orgId());

        if (criteria.orgId() != null) {
            where.append("AND o.org_id = :orgId ");
        }

        if (criteria.terminalId() != null) {
            where.append("AND o.terminal_id = :terminalId ");
            params.addValue("terminalId", criteria.terminalId());
        }

        // Status filter mapping
        String status = criteria.status();
        if (status != null && !status.isBlank()) {
            if ("VOID".equalsIgnoreCase(status)) {
                where.append("AND (o.isactive = 'N' OR o.order_status = 'VOID') AND o.order_no NOT LIKE '%\\_VOID\\_%' ESCAPE '\\' ");
            } else if ("PAID".equalsIgnoreCase(status)) {
                where.append("AND o.payment_status = 'PAID' AND o.isactive = 'Y' AND o.order_status <> 'VOID' AND o.order_no NOT LIKE '%\\_VOID\\_%' ESCAPE '\\' ");
            } else if ("COMPLETED_CANCELLED".equalsIgnoreCase(status)) {
                where.append("AND o.order_status IN ('COMPLETED', 'CANCELLED') AND o.isactive = 'Y' AND o.order_status <> 'VOID' AND o.order_no NOT LIKE '%\\_VOID\\_%' ESCAPE '\\' ");
            } else {
                where.append("AND o.order_status = :status AND o.isactive = 'Y' AND o.order_status <> 'VOID' AND o.order_no NOT LIKE '%\\_VOID\\_%' ESCAPE '\\' ");
                params.addValue("status", status);
            }
        } else {
            where.append("AND o.isactive = 'Y' AND o.order_status <> 'VOID' AND o.order_no NOT LIKE '%\\_VOID\\_%' ESCAPE '\\' ");
        }

        // Date range filters
        where.append("AND o.order_date >= :fromDate ");
        params.addValue("fromDate", java.sql.Timestamp.from(criteria.from()));

        where.append("AND o.order_date <= :toDate ");
        params.addValue("toDate", java.sql.Timestamp.from(criteria.to()));

        // Search text matching
        if (criteria.search() != null && !criteria.search().isEmpty()) {
            String searchPattern = "%" + criteria.search().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            String searchExact = criteria.search().toLowerCase();

            params.addValue("searchExact", searchExact);
            params.addValue("searchPattern", searchPattern);

            where.append("AND (");
            where.append("  LOWER(o.order_no) = :searchExact ");
            where.append("  OR EXISTS (SELECT 1 FROM invoices i WHERE i.order_id = o.id AND LOWER(i.invoice_no) = :searchExact) ");
            where.append("  OR EXISTS (SELECT 1 FROM payments p WHERE p.order_id = o.id AND LOWER(p.reference_no) = :searchExact) ");
            where.append("  OR LOWER(o.order_no) LIKE :searchPattern ESCAPE '\\' ");
            where.append("  OR EXISTS (SELECT 1 FROM invoices i WHERE i.order_id = o.id AND LOWER(i.invoice_no) LIKE :searchPattern ESCAPE '\\') ");
            where.append("  OR EXISTS (SELECT 1 FROM payments p WHERE p.order_id = o.id AND LOWER(p.reference_no) LIKE :searchPattern ESCAPE '\\') ");
            where.append("  OR LOWER(o.table_number) LIKE :searchPattern ESCAPE '\\' ");
            where.append("  OR EXISTS (");
            where.append("    SELECT 1 FROM customers c ");
            where.append("    WHERE c.id = o.customer_id ");
            where.append("      AND c.client_id = :clientId ");
            where.append("      AND (:orgId IS NULL OR c.org_id = :orgId) ");
            where.append("      AND c.isactive = 'Y' ");
            where.append("      AND (LOWER(c.name) LIKE :searchPattern ESCAPE '\\' OR LOWER(COALESCE(c.phone, '')) LIKE :searchPattern ESCAPE '\\')");
            where.append("  ) ");
            // Correlated EXISTS avoids producing a flattened customer/order_links row set
            // and allows PostgreSQL to stop after finding the first matching order link.
            where.append("  OR EXISTS (");
            where.append("    SELECT 1 FROM customers c ");
            where.append("    WHERE c.client_id = :clientId ");
            where.append("      AND (:orgId IS NULL OR c.org_id = :orgId) ");
            where.append("      AND c.isactive = 'Y' ");
            where.append("      AND (LOWER(c.name) LIKE :searchPattern ESCAPE '\\' OR LOWER(COALESCE(c.phone, '')) LIKE :searchPattern ESCAPE '\\') ");
            where.append("      AND EXISTS (");
            where.append("        SELECT 1 FROM jsonb_array_elements(c.order_links) link ");
            where.append("        WHERE link ->> 'orderId' IS NOT NULL ");
            where.append("          AND link ->> 'orderId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' ");
            where.append("          AND CAST(link ->> 'orderId' AS uuid) = o.id");
            where.append("      )");
            where.append("  ) ");
            where.append(") ");
        }

        return where.toString();
    }

    private SalesSummaryDto fetchSalesSummary(String whereClause, MapSqlParameterSource params) {
        String summarySql = "WITH filtered_orders AS ( " +
                "  SELECT o.id, o.total_amount, o.total_tax_amount, o.total_discount_amount, o.grand_total " +
                "  FROM orders o " +
                "  " + whereClause + " " +
                "), " +
                "line_totals AS ( " +
                "  SELECT " +
                "    ol.order_id, " +
                "    SUM(ol.quantity) AS items_sold, " +
                "    SUM( " +
                "      GREATEST( " +
                "        0, " +
                "        CASE " +
                "          WHEN ol.tax_type = 'INCLUSIVE' THEN ol.gross_line_amount / (1 + ol.tax_rate / 100.0) " +
                "          ELSE ol.gross_line_amount " +
                "        END - COALESCE(ol.taxable_amount, 0) " +
                "      ) " +
                "    ) AS discount " +
                "  FROM order_lines ol " +
                "  WHERE ol.order_id IN (SELECT id FROM filtered_orders) AND ol.isactive = 'Y' " +
                "  GROUP BY ol.order_id " +
                "), " +
                "latest_payment AS ( " +
                "  SELECT DISTINCT ON (p.order_id) " +
                "    p.order_id, " +
                "    p.round_off_amount " +
                "  FROM payments p " +
                "  WHERE p.order_id IN (SELECT id FROM filtered_orders) " +
                "  ORDER BY p.order_id, p.created_at DESC " +
                ") " +
                "SELECT " +
                "  COUNT(fo.id) as total_orders, " +
                "  COALESCE(SUM(fo.total_amount), 0) as total_revenue, " +
                "  COALESCE(SUM(fo.total_tax_amount), 0) as total_tax, " +
                "  COALESCE(SUM(COALESCE(lt.discount, fo.total_discount_amount, 0)), 0) as total_discount, " +
                "  COALESCE(SUM(fo.grand_total), 0) as grand_total, " +
                "  COALESCE(SUM(lp.round_off_amount), 0) as total_round_off, " +
                "  COALESCE(SUM(lt.items_sold), 0) as items_sold " +
                "FROM filtered_orders fo " +
                "LEFT JOIN line_totals lt ON lt.order_id = fo.id " +
                "LEFT JOIN latest_payment lp ON lp.order_id = fo.id";

        return jdbcTemplate.queryForObject(summarySql, params, (rs, rowNum) -> {
            long totalOrders = rs.getLong("total_orders");
            BigDecimal grandTotal = rs.getBigDecimal("grand_total");
            BigDecimal avg = totalOrders > 0
                    ? grandTotal.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            return SalesSummaryDto.builder()
                    .totalOrders(totalOrders)
                    .totalRevenue(rs.getBigDecimal("total_revenue"))
                    .avgOrderValue(avg)
                    .itemsSold(rs.getLong("items_sold"))
                    .totalTax(rs.getBigDecimal("total_tax"))
                    .totalDiscount(rs.getBigDecimal("total_discount"))
                    .grandTotal(grandTotal)
                    .totalRoundOff(rs.getBigDecimal("total_round_off"))
                    .build();
        });
    }

    private long fetchCount(String whereClause, MapSqlParameterSource params) {
        String countSql = "SELECT COUNT(o.id) FROM orders o " + whereClause;
        Long count = jdbcTemplate.queryForObject(countSql, params, Long.class);
        return count == null ? 0L : count;
    }

    private List<OrderSummaryDto> fetchOrdersList(String whereClause, MapSqlParameterSource params, int page, int size) {
        String listSql = "SELECT o.id, o.order_no, o.order_type, o.order_status, o.payment_status, o.fulfillment_type, " +
                "o.table_id, o.table_number, o.customer_id, " +
                "c.name AS customer_name, c.phone AS customer_phone, " +
                "o.is_credit, o.credit_customer_id, " +
                "o.total_amount, o.total_tax_amount, o.total_discount_amount, o.grand_total, o.gross_amount, " +
                "o.order_date, o.created_at, o.updated_at, o.created_by, o.updated_by, " +
                "(SELECT i.invoice_no FROM invoices i WHERE i.order_id = o.id LIMIT 1) AS invoice_no, " +
                "(SELECT i.daily_bill_no FROM invoices i WHERE i.order_id = o.id LIMIT 1) AS daily_bill_no, " +
                "(SELECT p.reference_no FROM payments p WHERE p.order_id = o.id ORDER BY p.created_at DESC LIMIT 1) AS payment_no, " +
                "o.description, o.warehouse_id, o.vendor_id " +
                "FROM orders o " +
                "LEFT JOIN customers c ON c.id = o.customer_id " +
                whereClause +
                "ORDER BY o.order_date DESC, o.id DESC " + // Secondary key provides deterministic ordering when multiple orders share the same order_date
                "LIMIT :limit OFFSET :offset";


        MapSqlParameterSource pageParams = new MapSqlParameterSource(params.getValues());
        pageParams.addValue("limit", size);
        pageParams.addValue("offset", page * size);

        List<OrderSummaryDto> orders = jdbcTemplate.query(listSql, pageParams, (rs, rowNum) -> {
            Calendar utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            return OrderSummaryDto.builder()
                    .id(getUUID(rs, "id"))
                    .orderNo(rs.getString("order_no"))
                    .orderType(OrderType.valueOf(rs.getString("order_type")))
                    .orderStatus(rs.getString("order_status"))
                    .paymentStatus(rs.getString("payment_status"))
                    .fulfillmentType(rs.getString("fulfillment_type"))
                    .tableId(getUUID(rs, "table_id"))
                    .tableNumber(rs.getString("table_number"))
                    .customerId(getUUID(rs, "customer_id"))
                    .customerName(rs.getString("customer_name"))
                    .customerPhone(rs.getString("customer_phone"))
                    .isCredit(rs.getBoolean("is_credit"))
                    .creditCustomerId(getUUID(rs, "credit_customer_id"))
                    .totalAmount(rs.getBigDecimal("total_amount"))
                    .totalTaxAmount(rs.getBigDecimal("total_tax_amount"))
                    .totalDiscountAmount(rs.getBigDecimal("total_discount_amount"))
                    .grandTotal(rs.getBigDecimal("grand_total"))
                    .grossAmount(rs.getBigDecimal("gross_amount"))
                    .orderDate(rs.getTimestamp("order_date", utcCal) != null ? rs.getTimestamp("order_date", utcCal).toInstant() : null)
                    .createdAt(rs.getTimestamp("created_at", utcCal) != null ? rs.getTimestamp("created_at", utcCal).toLocalDateTime() : null)
                    .updatedAt(rs.getTimestamp("updated_at", utcCal) != null ? rs.getTimestamp("updated_at", utcCal).toLocalDateTime() : null)
                    .createdBy(rs.getString("created_by"))
                    .updatedBy(rs.getString("updated_by"))
                    .invoiceNo(rs.getString("invoice_no"))
                    .dailyBillNo(rs.getInt("daily_bill_no"))
                    .paymentNo(rs.getString("payment_no"))
                    .description(rs.getString("description"))
                    .warehouseId(getUUID(rs, "warehouse_id"))
                    .vendorId(getUUID(rs, "vendor_id"))
                    .lines(new ArrayList<>())
                    .build();
        });

        if (!orders.isEmpty()) {
            List<UUID> orderIds = orders.stream().map(order -> order.getId()).toList();
            MapSqlParameterSource linesParams = new MapSqlParameterSource("orderIds", orderIds);
            String linesSql = "SELECT id, order_id, product_id, variant_id, product_name, category_name, is_packaged_good, quantity, unit_of_measure, uom_precision, unit_price, tax_rate, tax_amount, discount_amount, line_total, gross_line_amount, unit_price_ex_tax, taxable_amount, tax_type " +
                    "FROM order_lines " +
                    "WHERE order_id IN (:orderIds) AND isactive = 'Y' " +
                    "ORDER BY order_id, created_at, id";

            List<Map<String, Object>> allLines = jdbcTemplate.queryForList(linesSql, linesParams);

            Map<UUID, List<OrderLineSummaryDto>> linesByOrderId = new HashMap<>();
            for (Map<String, Object> row : allLines) {
                UUID orderId = (UUID) row.get("order_id");
                String taxTypeStr = (String) row.get("tax_type");

                OrderLineSummaryDto lineDto = OrderLineSummaryDto.builder()
                        .id((UUID) row.get("id"))
                        .productId((UUID) row.get("product_id"))
                        .variantId((UUID) row.get("variant_id"))
                        .productName((String) row.get("product_name"))
                        .categoryName((String) row.get("category_name"))
                        .isPackagedGood((Boolean) row.get("is_packaged_good"))
                        .quantity((BigDecimal) row.get("quantity"))
                        .unitOfMeasure((String) row.get("unit_of_measure"))
                        .uomPrecision((Integer) row.get("uom_precision"))
                        .unitPrice((BigDecimal) row.get("unit_price"))
                        .taxRate((BigDecimal) row.get("tax_rate"))
                        .taxAmount((BigDecimal) row.get("tax_amount"))
                        .discountAmount((BigDecimal) row.get("discount_amount"))
                        .lineTotal((BigDecimal) row.get("line_total"))
                        .grossLineAmount((BigDecimal) row.get("gross_line_amount"))
                        .unitPriceExTax((BigDecimal) row.get("unit_price_ex_tax"))
                        .taxableAmount((BigDecimal) row.get("taxable_amount"))
                        .taxType(taxTypeStr != null ? com.restaurant.pos.order.domain.TaxType.valueOf(taxTypeStr) : null)
                        .build();

                linesByOrderId.computeIfAbsent(orderId, k -> new ArrayList<>()).add(lineDto);
            }

            orders.forEach(dto -> {
                dto.setLines(linesByOrderId.getOrDefault(dto.getId(), List.of()));
            });
        }

        return orders;
    }

    private UUID getUUID(java.sql.ResultSet rs, String columnLabel) throws java.sql.SQLException {
        String val = rs.getString(columnLabel);
        return val != null ? UUID.fromString(val) : null;
    }

    private record SalesQueryCriteria(
            UUID clientId,
            UUID orgId,
            UUID terminalId,
            Instant from,
            Instant to,
            String search,
            String status
    ) {}
}
