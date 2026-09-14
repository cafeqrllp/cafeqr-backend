package com.restaurant.pos.pos.sale.dto;

import com.restaurant.pos.pos.sale.query.PosSaleSummaryView;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for incremental live order polling.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>Full snapshot</b> ({@code isFullSnapshot = true}): returned on first request
 *       or when {@code updatedAfter} is null. Contains all currently live orders.</li>
 *   <li><b>Incremental update</b> ({@code isFullSnapshot = false}): returned when
 *       {@code updatedAfter} is provided. Contains only orders changed since that
 *       timestamp, plus IDs of orders that have been removed from "live" status
 *       (completed, cancelled, voided).</li>
 * </ul>
 * </p>
 * <p>
 * The frontend should:
 * <ol>
 *   <li>First request: {@code GET /live} → full snapshot, store {@code serverTime}</li>
 *   <li>Subsequent requests: {@code GET /live?updatedAfter={serverTime}} → merge
 *       changed orders and remove {@code removedOrderIds} from local state</li>
 * </ol>
 * </p>
 *
 * @param orders          live orders (all if full snapshot, changed-only if incremental)
 * @param removedOrderIds IDs of orders no longer in "live" status (only in incremental mode)
 * @param serverTime      server clock at response time — use as {@code updatedAfter} for next poll
 * @param isFullSnapshot  true if this is a complete snapshot, false if incremental delta
 */
public record LiveOrdersResponse(
        List<PosSaleSummaryView> orders,
        List<UUID> removedOrderIds,
        Instant serverTime,
        boolean isFullSnapshot,
        boolean hasMore,
        Instant nextCursorTime,
        UUID nextCursorId,
        String nextCursor
) {

    public record CursorPoint(Instant timestamp, UUID id) {}

    /**
     * Encodes a (timestamp, id) pair into a URL-safe Base64 opaque cursor.
     */
    public static String encodeCursor(Instant timestamp, UUID id) {
        if (timestamp == null) return null;
        String raw = timestamp.toEpochMilli() + ":" + (id != null ? id.toString() : "");
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Decodes a URL-safe Base64 opaque cursor into a CursorPoint(timestamp, id).
     */
    public static CursorPoint decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            byte[] bytes = java.util.Base64.getUrlDecoder().decode(cursor);
            String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 2);
            long epochMs = Long.parseLong(parts[0]);
            Instant instant = Instant.ofEpochMilli(epochMs);
            UUID id = (parts.length > 1 && !parts[1].isBlank()) ? UUID.fromString(parts[1]) : null;
            return new CursorPoint(instant, id);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Creates a full snapshot response with hasMore flag.
     */
    public static LiveOrdersResponse fullSnapshot(
            List<PosSaleSummaryView> orders,
            Instant serverTime,
            boolean hasMore) {
        return new LiveOrdersResponse(
                orders != null ? orders : Collections.emptyList(),
                Collections.emptyList(),
                serverTime,
                true,
                hasMore,
                null,
                null,
                null
        );
    }

    /**
     * Backward-compatible full snapshot response.
     */
    public static LiveOrdersResponse fullSnapshot(List<PosSaleSummaryView> orders, Instant serverTime) {
        return fullSnapshot(orders, serverTime, false);
    }

    /**
     * Creates an incremental delta response with pagination and cursor details.
     */
    public static LiveOrdersResponse incremental(
            List<PosSaleSummaryView> changedOrders,
            List<UUID> removedOrderIds,
            Instant serverTime,
            boolean hasMore,
            Instant nextCursorTime,
            UUID nextCursorId) {
        String cursor = encodeCursor(nextCursorTime, nextCursorId);
        return new LiveOrdersResponse(
                changedOrders != null ? changedOrders : Collections.emptyList(),
                removedOrderIds != null ? removedOrderIds : Collections.emptyList(),
                serverTime,
                false,
                hasMore,
                nextCursorTime,
                nextCursorId,
                cursor
        );
    }

    /**
     * Backward-compatible incremental delta response.
     */
    public static LiveOrdersResponse incremental(
            List<PosSaleSummaryView> changedOrders,
            List<UUID> removedOrderIds,
            Instant serverTime) {
        return incremental(changedOrders, removedOrderIds, serverTime, false, null, null);
    }
}
