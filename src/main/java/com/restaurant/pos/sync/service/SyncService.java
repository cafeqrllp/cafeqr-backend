package com.restaurant.pos.sync.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderStatus;
import com.restaurant.pos.order.domain.PaymentStatus;
import com.restaurant.pos.order.dto.OrderSettleRequest;
import com.restaurant.pos.order.dto.OrderCancelRequest;
import com.restaurant.pos.order.dto.OrderCreditCompletionRequest;
import com.restaurant.pos.order.dto.OrderMoveTableRequest;
import com.restaurant.pos.order.service.OrderService;
import com.restaurant.pos.product.domain.Category;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.domain.Uom;
import com.restaurant.pos.product.domain.VariantGroup;
import com.restaurant.pos.product.domain.VariantOption;
import com.restaurant.pos.product.service.ProductService;
import com.restaurant.pos.sync.dto.SyncBootstrapResponse;
import com.restaurant.pos.sync.dto.SyncChangesResponse;
import com.restaurant.pos.sync.dto.SyncOperationRequest;
import com.restaurant.pos.sync.dto.SyncOperationResult;
import com.restaurant.pos.sync.dto.SyncPushRequest;
import com.restaurant.pos.sync.dto.SyncPushResponse;
import com.restaurant.pos.table.domain.RestaurantTable;
import com.restaurant.pos.table.service.RestaurantTableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    private final ProductService productService;
    private final OrderService orderService;
    private final RestaurantTableService tableService;
    private final SystemConfigurationService configurationService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    @Transactional(readOnly = true)
    public SyncBootstrapResponse bootstrap() {
        validateOfflineSyncEnabled();
        Instant now = Instant.now();
        return SyncBootstrapResponse.builder()
                .serverTime(now)
                .syncToken(generateSyncToken(now))
                .products(productService.getProducts())
                .categories(productService.getCategories())
                .uoms(productService.getUoms())
                .variantGroups(productService.getVariantGroups())
                .tables(tableService.getAllTables())
                .orders(orderService.getSyncBootstrapOrders())
                .configuration(configurationService.getConfiguration())
                .build();
    }

    @Transactional(readOnly = true)
    public SyncChangesResponse changes(Instant since) {
        validateOfflineSyncEnabled();
        Instant now = Instant.now();
        return SyncChangesResponse.builder()
                .since(since)
                .serverTime(now)
                .syncToken(generateSyncToken(now))
                .products(productService.getProductsChangedSince(since))
                .categories(productService.getCategoriesChangedSince(since))
                .uoms(productService.getUomsChangedSince(since))
                .variantGroups(productService.getVariantGroupsChangedSince(since))
                .tables(tableService.getTablesChangedSince(since))
                .orders(orderService.getChangedSalesOrders(since))
                .configuration(configurationService.getConfiguration())
                .build();
    }

    /**
     * Re-processes all FAILED_PERMANENT operations for the current tenant.
     * This is used after adding new dispatch routes (e.g. /settle, /bill) to
     * recover operations that were previously unsupported.
     *
     * @return a summary map with counts of recovered, still-failed, and skipped operations
     */
    public Map<String, Object> replayFailedOperations() {
        UUID clientId = TenantContext.getCurrentTenant();
        log.info("[Offline Sync Recovery] Starting replay of FAILED_PERMANENT operations for clientId={}", clientId);

        List<Map<String, Object>> failedRows = jdbcTemplate.queryForList(
                """
                SELECT id, operation_id, method, url, payload_json::text AS payload_text, error_message
                FROM sync_operations
                WHERE client_id = ? AND status = 'FAILED_PERMANENT'
                ORDER BY created_at ASC
                """,
                clientId
        );

        int recovered = 0;
        int stillFailed = 0;
        int skipped = 0;

        for (Map<String, Object> row : failedRows) {
            String operationId = (String) row.get("operation_id");
            String id = row.get("id").toString();
            String payloadText = (String) row.get("payload_text");

            try {
                Object payloadObj = payloadText != null ? objectMapper.readValue(payloadText, Object.class) : null;

                SyncOperationRequest replayOp = new SyncOperationRequest();
                replayOp.setId(operationId);
                replayOp.setOperationId(operationId);
                replayOp.setMethod((String) row.get("method"));
                replayOp.setUrl((String) row.get("url"));
                replayOp.setPayload(payloadObj);

                Object result = transactionTemplate.execute(status -> {
                    try {
                        return dispatch(replayOp);
                    } catch (Exception ex) {
                        status.setRollbackOnly();
                        throw ex;
                    }
                });

                // Update the record to SYNCED
                String responseJson = objectMapper.writeValueAsString(
                        SyncOperationResult.builder()
                                .operationId(operationId)
                                .success(true)
                                .status("SYNCED")
                                .message("Recovered via replay")
                                .data(result)
                                .build());
                jdbcTemplate.update(
                        """
                        UPDATE sync_operations
                        SET status = 'SYNCED', error_message = NULL,
                            response_json = CAST(? AS jsonb),
                            processed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                        WHERE id = CAST(? AS uuid)
                        """,
                        responseJson, id
                );

                log.info("[Offline Sync Recovery]   ✓ Recovered operationId={}", operationId);
                recovered++;
            } catch (IllegalArgumentException ex) {
                // Still unsupported — skip
                log.warn("[Offline Sync Recovery]   ⊘ Still unsupported operationId={}: {}", operationId, ex.getMessage());
                skipped++;
            } catch (Exception ex) {
                log.error("[Offline Sync Recovery]   ✗ Failed to replay operationId={}: {}", operationId, ex.getMessage(), ex);

                // Update error message but keep status as FAILED_PERMANENT
                try {
                    jdbcTemplate.update(
                            """
                            UPDATE sync_operations
                            SET error_message = ?, updated_at = CURRENT_TIMESTAMP
                            WHERE id = CAST(? AS uuid)
                            """,
                            "Replay failed: " + ex.getMessage(), id
                    );
                } catch (Exception ignored) {}

                stillFailed++;
            }
        }

        log.info("[Offline Sync Recovery] Complete for clientId={}. Total={}, Recovered={}, StillFailed={}, Skipped={}",
                clientId, failedRows.size(), recovered, stillFailed, skipped);

        return Map.of(
                "total", failedRows.size(),
                "recovered", recovered,
                "stillFailed", stillFailed,
                "skipped", skipped
        );
    }

    public Instant decodeSyncToken(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            byte[] decodedBytes = java.util.Base64.getDecoder().decode(token);
            long epochMilli = Long.parseLong(new String(decodedBytes));
            return Instant.ofEpochMilli(epochMilli);
        } catch (Exception e) {
            log.warn("Invalid sync token received: {}", token, e);
            return null;
        }
    }

    private String generateSyncToken(Instant time) {
        if (time == null) return null;
        return java.util.Base64.getEncoder().encodeToString(String.valueOf(time.toEpochMilli()).getBytes());
    }

    public SyncPushResponse push(SyncPushRequest request) {
        validateOfflineSyncEnabled();
        // Schema Version check
        if (request.getSchemaVersion() != null && request.getSchemaVersion() < 1) {
            throw new IllegalArgumentException("Unsupported offline sync schema version: " + request.getSchemaVersion());
        }

        int totalOps = request.getOperations() == null ? 0 : request.getOperations().size();
        log.info("[Offline Sync Push] Received {} operations from client={}, org={}, terminal={}",
                totalOps, TenantContext.getCurrentTenant(), TenantContext.getCurrentOrg(), TenantContext.getCurrentTerminal());

        java.util.Set<String> failedOperationIds = new java.util.HashSet<>();
        java.util.List<SyncOperationResult> results = new java.util.ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (SyncOperationRequest operation : request.getOperations()) {
            SyncOperationResult result = processOperationSafely(operation, failedOperationIds);
            results.add(result);
            if (result.isSuccess()) {
                successCount++;
            } else {
                failCount++;
                failedOperationIds.add(result.getOperationId() != null ? result.getOperationId() : operation.getId());
            }
        }

        log.info("[Offline Sync Push] Completed. total={}, success={}, failed={}, client={}",
                totalOps, successCount, failCount, TenantContext.getCurrentTenant());

        return SyncPushResponse.builder()
                .serverTime(Instant.now())
                .results(results)
                .build();
    }

    private SyncOperationResult processOperationSafely(SyncOperationRequest operation) {
        return processOperationSafely(operation, new java.util.HashSet<>());
    }

    private SyncOperationResult processOperationSafely(SyncOperationRequest operation, java.util.Set<String> failedOperationIds) {
        String operationId = operation.getOperationId() != null ? operation.getOperationId() : operation.getId();
        if (operationId == null || operationId.isBlank()) {
            return SyncOperationResult.builder()
                    .operationId(null)
                    .success(false)
                    .status("REJECTED")
                    .message("Missing operationId")
                    .build();
        }

        // DAG check: Check if parent operation failed
        if (operation.getDependsOnOperationId() != null && !operation.getDependsOnOperationId().isBlank() 
                && failedOperationIds.contains(operation.getDependsOnOperationId())) {
            log.warn("Skipping operation {} because parent operation {} failed.", operationId, operation.getDependsOnOperationId());
            SyncOperationResult result = SyncOperationResult.builder()
                    .operationId(operationId)
                    .success(false)
                    .status("SKIPPED_DEPENDENCY")
                    .message("Skipped because parent operation " + operation.getDependsOnOperationId() + " failed.")
                    .build();
            try {
                transactionTemplate.execute(status -> {
                    storeOperation(operation, result);
                    return null;
                });
            } catch (Exception ex) {
                log.warn("Failed to store skipped dependency operation. operationId={}", operationId, ex);
            }
            return result;
        }

        SyncOperationResult existing = findStoredResult(operationId);
        if (existing != null && existing.isSuccess()) {
            return existing;
        }

        try {
            return transactionTemplate.execute(status -> {
                try {
                    Object data = dispatch(operation);
                    SyncOperationResult result = SyncOperationResult.builder()
                            .operationId(operationId)
                            .success(true)
                            .status("SYNCED")
                            .message("Synced")
                            .data(data)
                            .build();
                    storeOperation(operation, result);
                    log.info("[Offline Sync Push]   ✓ operationId={} method={} path={}",
                            operationId, safeMethod(operation), resolvePath(operation));
                    return result;
                } catch (Exception ex) {
                    status.setRollbackOnly();
                    throw ex;
                }
            });
        } catch (Exception ex) {
            log.warn("[Offline Sync Push]   ✗ operationId={} method={} path={} error={}",
                    operationId, safeMethod(operation), resolvePath(operation), ex.getMessage());
            
            // Exception Classification: Permanent vs Retryable
            String status = classifyFailure(ex);
            
            SyncOperationResult result = SyncOperationResult.builder()
                    .operationId(operationId)
                    .success(false)
                    .status(status)
                    .message(ex.getMessage())
                    .build();
            
            try {
                transactionTemplate.execute(txStatus -> {
                    storeOperation(operation, result);
                    return null;
                });
            } catch (Exception storeEx) {
                log.error("Failed to store failed sync operation. operationId={}", operationId, storeEx);
            }
            return result;
        }
    }

    private String classifyFailure(Exception ex) {
        if (ex instanceof IllegalArgumentException 
                || ex instanceof NullPointerException 
                || ex instanceof ClassCastException
                || ex instanceof com.restaurant.pos.common.exception.BusinessException
                || ex instanceof com.fasterxml.jackson.core.JsonProcessingException
                || (ex.getMessage() != null && ex.getMessage().toLowerCase().contains("validation"))) {
            return "FAILED_PERMANENT";
        }
        return "FAILED_RETRYABLE";
    }

    private Object dispatch(SyncOperationRequest operation) {
        String method = safeMethod(operation);
        String path = resolvePath(operation);

        if ("POST".equals(method) && "/api/v1/orders".equals(path)) {
            Order order = convert(operation.getPayload(), Order.class);
            String operationId = operation.getOperationId() != null ? operation.getOperationId() : operation.getId();
            var existingOrder = orderService.findBySourceOperationId(operationId);
            if (existingOrder.isPresent()) {
                return existingOrder.get();
            }
            if (order.getSourceOperationId() == null) order.setSourceOperationId(operationId);
            if (order.getSourceOfflineId() == null) order.setSourceOfflineId(operation.getOfflineId());
            if (order.getSourceLocalRef() == null) order.setSourceLocalRef(operation.getClientRequestId());
            if (order.getSyncOrigin() == null || order.getSyncOrigin().isBlank()) order.setSyncOrigin("OFFLINE_QUEUE");
            return orderService.createOrder(order);
        }
        if ("POST".equals(method) && path.startsWith("/api/v1/orders/") && path.endsWith("/settle")) {
            UUID orderId = resolveRealOrderId(extractUuid(path, 3));
            ensureTerminalContext(orderId);
            try {
                Order order = orderService.getOrder(orderId);
                if ("COMPLETED".equalsIgnoreCase(order.getOrderStatus()) || "PAID".equalsIgnoreCase(order.getPaymentStatus())) {
                    log.info("[Sync Service] Order {} is already settled/completed. Treating settle as successful no-op.", orderId);
                    return order;
                }
            } catch (Exception ignored) {}
            OrderSettleRequest settleRequest = convert(operation.getPayload(), OrderSettleRequest.class);
            return orderService.settleOrder(orderId, settleRequest);
        }
        if ("POST".equals(method) && path.startsWith("/api/v1/orders/") && path.endsWith("/bill")) {
            UUID orderId = resolveRealOrderId(extractUuid(path, 3));
            ensureTerminalContext(orderId);
            try {
                Order order = orderService.getOrder(orderId);
                if ("BILLED".equalsIgnoreCase(order.getOrderStatus()) || "COMPLETED".equalsIgnoreCase(order.getOrderStatus()) || "PAID".equalsIgnoreCase(order.getPaymentStatus())) {
                    log.info("[Sync Service] Order {} is already billed/completed. Treating bill as successful no-op.", orderId);
                    return order;
                }
            } catch (Exception ignored) {}
            @SuppressWarnings("unchecked")
            List<String> skipPrintKinds = operation.getPayload() != null
                    ? objectMapper.convertValue(
                            ((java.util.Map<String, Object>) operation.getPayload()).get("skipAutoPrintKinds"),
                            new TypeReference<List<String>>() {})
                    : null;
            return orderService.billOrder(orderId, skipPrintKinds);
        }
        if ("POST".equals(method) && path.startsWith("/api/v1/orders/") && path.endsWith("/cancel")) {
            UUID orderId = resolveRealOrderId(extractUuid(path, 3));
            ensureTerminalContext(orderId);
            try {
                Order order = orderService.getOrder(orderId);
                if ("CANCELLED".equalsIgnoreCase(order.getOrderStatus()) || "VOID".equalsIgnoreCase(order.getOrderStatus())) {
                    log.info("[Sync Service] Order {} is already cancelled. Treating cancel as successful no-op.", orderId);
                    return order;
                }
            } catch (Exception ignored) {}
            OrderCancelRequest cancelRequest = operation.getPayload() != null
                    ? convert(operation.getPayload(), OrderCancelRequest.class)
                    : null;
            return orderService.cancelOrder(orderId, cancelRequest);
        }
        if ("POST".equals(method) && path.startsWith("/api/v1/orders/") && path.endsWith("/complete-credit")) {
            UUID orderId = resolveRealOrderId(extractUuid(path, 3));
            ensureTerminalContext(orderId);
            OrderCreditCompletionRequest creditRequest = operation.getPayload() != null
                    ? convert(operation.getPayload(), OrderCreditCompletionRequest.class)
                    : null;
            return orderService.completeCreditOrder(orderId, creditRequest);
        }
        if ("POST".equals(method) && path.startsWith("/api/v1/orders/") && path.endsWith("/move-table")) {
            UUID orderId = resolveRealOrderId(extractUuid(path, 3));
            OrderMoveTableRequest moveRequest = convert(operation.getPayload(), OrderMoveTableRequest.class);
            return orderService.moveTable(orderId, moveRequest);
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/orders/")) {
            return orderService.updateOrder(resolveRealOrderId(extractUuid(path, 3)), convert(operation.getPayload(), Order.class));
        }
        if ("PATCH".equals(method) && path.startsWith("/api/v1/orders/") && path.endsWith("/status")) {
            UUID orderId = resolveRealOrderId(extractUuid(path, 3));
            String statusStr = stringParam(operation, "status");
            String paymentStatusStr = stringParam(operation, "paymentStatus");
            String description = stringParam(operation, "description");
            OrderStatus status = statusStr != null ? OrderStatus.valueOf(statusStr.toUpperCase()) : null;
            PaymentStatus paymentStatus = paymentStatusStr != null ? PaymentStatus.valueOf(paymentStatusStr.toUpperCase()) : null;
            return orderService.updateOrderStatus(orderId, status, paymentStatus, description);
        }

        if ("POST".equals(method) && "/api/v1/products".equals(path)) {
            return productService.createProduct(convert(operation.getPayload(), Product.class));
        }
        if ("POST".equals(method) && "/api/v1/products/bulk".equals(path)) {
            List<Product> products = objectMapper.convertValue(operation.getPayload(), new TypeReference<List<Product>>() {});
            return productService.bulkCreateProducts(products);
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/products/")) {
            return productService.updateProduct(extractUuid(path, 3), convert(operation.getPayload(), Product.class));
        }
        if ("DELETE".equals(method) && path.startsWith("/api/v1/products/")) {
            productService.deleteProduct(extractUuid(path, 3));
            return Map.of("deleted", true);
        }

        if ("POST".equals(method) && "/api/v1/products/categories".equals(path)) {
            return productService.createCategory(convert(operation.getPayload(), Category.class));
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/products/categories/")) {
            return productService.updateCategory(extractUuid(path, 4), convert(operation.getPayload(), Category.class));
        }
        if ("DELETE".equals(method) && path.startsWith("/api/v1/products/categories/")) {
            productService.deleteCategory(extractUuid(path, 4));
            return Map.of("deleted", true);
        }

        if ("POST".equals(method) && "/api/v1/products/uoms".equals(path)) {
            return productService.createUom(convert(operation.getPayload(), Uom.class));
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/products/uoms/")) {
            return productService.updateUom(extractUuid(path, 4), convert(operation.getPayload(), Uom.class));
        }
        if ("DELETE".equals(method) && path.startsWith("/api/v1/products/uoms/")) {
            productService.deleteUom(extractUuid(path, 4));
            return Map.of("deleted", true);
        }

        if ("POST".equals(method) && "/api/v1/products/variants/groups".equals(path)) {
            return productService.createVariantGroup(convert(operation.getPayload(), VariantGroup.class));
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/products/variants/groups/")) {
            return productService.updateVariantGroup(extractUuid(path, 5), convert(operation.getPayload(), VariantGroup.class));
        }
        if ("DELETE".equals(method) && path.startsWith("/api/v1/products/variants/groups/")) {
            productService.deleteVariantGroup(extractUuid(path, 5));
            return Map.of("deleted", true);
        }

        if ("POST".equals(method) && "/api/v1/products/variants/options".equals(path)) {
            return productService.createVariantOption(convert(operation.getPayload(), VariantOption.class));
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/products/variants/options/")) {
            return productService.updateVariantOption(extractUuid(path, 5), convert(operation.getPayload(), VariantOption.class));
        }
        if ("DELETE".equals(method) && path.startsWith("/api/v1/products/variants/options/")) {
            productService.deleteVariantOption(extractUuid(path, 5));
            return Map.of("deleted", true);
        }

        if ("POST".equals(method) && "/api/v1/tables".equals(path)) {
            return tableService.saveTable(convert(operation.getPayload(), RestaurantTable.class));
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/tables/")) {
            RestaurantTable table = convert(operation.getPayload(), RestaurantTable.class);
            table.setId(extractUuid(path, 3));
            return tableService.saveTable(table);
        }
        if ("DELETE".equals(method) && path.startsWith("/api/v1/tables/")) {
            tableService.deleteTable(extractUuid(path, 3));
            return Map.of("deleted", true);
        }

        if ("PUT".equals(method) && "/api/v1/configurations".equals(path)) {
            return configurationService.updateConfiguration(convert(operation.getPayload(), ConfigurationDto.class));
        }

        throw new IllegalArgumentException("Unsupported offline sync operation: " + method + " " + path);
    }

    private <T> T convert(Object payload, Class<T> type) {
        return objectMapper.convertValue(payload, type);
    }

    private String safeMethod(SyncOperationRequest operation) {
        return (operation.getMethod() == null ? "GET" : operation.getMethod()).toUpperCase(Locale.ROOT);
    }

    private String resolvePath(SyncOperationRequest operation) {
        if (operation.getPath() != null && !operation.getPath().isBlank()) {
            return operation.getPath();
        }
        String url = operation.getUrl();
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            return URI.create(url).getPath();
        } catch (Exception ignored) {
            int queryIndex = url.indexOf('?');
            return queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        }
    }

    private UUID extractUuid(String path, int segmentIndex) {
        String[] parts = path.split("/");
        if (parts.length <= segmentIndex + 1) {
            throw new IllegalArgumentException("Missing id in path: " + path);
        }
        return UUID.fromString(parts[segmentIndex + 1]);
    }

    private String stringParam(SyncOperationRequest operation, String key) {
        Object value = operation.getParams() == null ? null : operation.getParams().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Ensures TenantContext has a terminal ID set before dispatching command operations
     * (settle, bill, cancel, etc.) that require it for offline document sequence leasing.
     * The terminal ID is resolved from the order entity stored in the database.
     */
    private void ensureTerminalContext(UUID orderId) {
        if (TenantContext.getCurrentTerminal() != null) {
            return; // Already set (e.g. from X-Terminal-ID header)
        }
        try {
            Order order = orderService.getOrder(orderId);
            UUID terminalId = order.getTerminalId() != null ? order.getTerminalId()
                    : order.getSourceTerminalId();
            if (terminalId != null) {
                TenantContext.setCurrentTerminal(terminalId);
                log.debug("[Offline Sync] Set terminal context from order {} → {}", orderId, terminalId);
            }
        } catch (Exception ex) {
            log.warn("[Offline Sync] Could not resolve terminal from order {}: {}", orderId, ex.getMessage());
        }
    }

    private SyncOperationResult findStoredResult(String operationId) {
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT response_json::text FROM sync_operations WHERE client_id = ? AND operation_id = ?",
                    String.class,
                    TenantContext.getCurrentTenant(),
                    operationId
            );
            return json == null ? null : objectMapper.readValue(json, SyncOperationResult.class);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        } catch (Exception ex) {
            log.warn("Unable to read stored sync operation result. operationId={}", operationId, ex);
            return null;
        }
    }

    private void storeOperation(SyncOperationRequest operation, SyncOperationResult result) {
        try {
            String responseJson = objectMapper.writeValueAsString(result);
            String payloadJson = objectMapper.writeValueAsString(operation.getPayload());
            jdbcTemplate.update("""
                    INSERT INTO sync_operations (
                        client_id, org_id, terminal_id, operation_id, client_request_id,
                        offline_id, method, url, entity, status, error_message,
                        payload_json, response_json, processed_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (client_id, operation_id) DO UPDATE SET
                        status = EXCLUDED.status,
                        error_message = EXCLUDED.error_message,
                        response_json = EXCLUDED.response_json,
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    TenantContext.getCurrentTenant(),
                    TenantContext.getCurrentOrg(),
                    TenantContext.getCurrentTerminal(),
                    result.getOperationId(),
                    operation.getClientRequestId(),
                    operation.getOfflineId(),
                    safeMethod(operation),
                    operation.getUrl(),
                    operation.getEntity(),
                    result.getStatus(),
                    result.isSuccess() ? null : result.getMessage(),
                    payloadJson,
                    responseJson
            );
        } catch (Exception ex) {
            log.warn("Unable to record sync operation. operationId={}", result.getOperationId(), ex);
        }
    }

    private UUID resolveRealOrderId(UUID clientOrderId) {
        if (clientOrderId == null) return null;
        try {
            orderService.getOrder(clientOrderId);
            return clientOrderId;
        } catch (Exception e) {
            return orderService.findBySourceOperationId(clientOrderId.toString())
                    .map(Order::getId)
                    .orElse(clientOrderId);
        }
    }

    private void validateOfflineSyncEnabled() {
        ConfigurationDto config = configurationService.getConfiguration();
        if (config == null || !config.isOfflineSyncEnabled()) {
            throw new BusinessException("Offline Sync is disabled for this organization.");
        }
    }
}
