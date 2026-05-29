package com.restaurant.pos.sync.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.order.domain.Order;
import com.restaurant.pos.order.domain.OrderStatus;
import com.restaurant.pos.order.domain.PaymentStatus;
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

    @Transactional(readOnly = true)
    public SyncBootstrapResponse bootstrap() {
        return SyncBootstrapResponse.builder()
                .serverTime(Instant.now())
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
        return SyncChangesResponse.builder()
                .since(since)
                .serverTime(Instant.now())
                .products(productService.getProductsChangedSince(since))
                .categories(productService.getCategoriesChangedSince(since))
                .uoms(productService.getUomsChangedSince(since))
                .variantGroups(productService.getVariantGroupsChangedSince(since))
                .tables(tableService.getTablesChangedSince(since))
                .orders(orderService.getChangedSalesOrders(since))
                .configuration(configurationService.getConfiguration())
                .build();
    }

    @Transactional
    public SyncPushResponse push(SyncPushRequest request) {
        // Schema Version check
        if (request.getSchemaVersion() != null && request.getSchemaVersion() < 1) {
            throw new IllegalArgumentException("Unsupported offline sync schema version: " + request.getSchemaVersion());
        }

        java.util.Set<String> failedOperationIds = new java.util.HashSet<>();
        java.util.List<SyncOperationResult> results = new java.util.ArrayList<>();

        for (SyncOperationRequest operation : request.getOperations()) {
            SyncOperationResult result = processOperationSafely(operation, failedOperationIds);
            results.add(result);
            if (!result.isSuccess()) {
                failedOperationIds.add(result.getOperationId() != null ? result.getOperationId() : operation.getId());
            }
        }

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
            storeOperation(operation, result);
            return result;
        }

        SyncOperationResult existing = findStoredResult(operationId);
        if (existing != null) {
            return existing;
        }

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
            return result;
        } catch (Exception ex) {
            log.warn("Offline sync operation failed. operationId={}, method={}, path={}, message={}",
                    operationId, operation.getMethod(), resolvePath(operation), ex.getMessage());
            
            // Exception Classification: Permanent vs Retryable
            String status = classifyFailure(ex);
            
            SyncOperationResult result = SyncOperationResult.builder()
                    .operationId(operationId)
                    .success(false)
                    .status(status)
                    .message(ex.getMessage())
                    .build();
            storeOperation(operation, result);
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
        if ("PUT".equals(method) && path.startsWith("/api/v1/orders/")) {
            return orderService.updateOrder(extractUuid(path, 3), convert(operation.getPayload(), Order.class));
        }
        if ("PATCH".equals(method) && path.startsWith("/api/v1/orders/") && path.endsWith("/status")) {
            UUID orderId = extractUuid(path, 3);
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
                    ON CONFLICT (client_id, operation_id) DO NOTHING
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
}
