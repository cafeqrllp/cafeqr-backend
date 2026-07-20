package com.restaurant.pos.sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.restaurant.pos.common.dto.ConfigurationDto;
import com.restaurant.pos.common.service.SystemConfigurationService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.order.service.OrderService;
import com.restaurant.pos.product.service.ProductService;
import com.restaurant.pos.sync.dto.*;
import com.restaurant.pos.table.service.RestaurantTableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SyncServiceTest {

    private ProductService productService;
    private OrderService orderService;
    private RestaurantTableService tableService;
    private SystemConfigurationService configurationService;
    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private TransactionTemplate transactionTemplate;

    private SyncService syncService;
    private UUID tenantId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        productService = mock(ProductService.class);
        orderService = mock(OrderService.class);
        tableService = mock(RestaurantTableService.class);
        configurationService = mock(SystemConfigurationService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        transactionTemplate = mock(TransactionTemplate.class);

        // Mock TransactionTemplate to immediately execute the callback
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer((Answer<Object>) invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        syncService = new SyncService(
                productService,
                orderService,
                tableService,
                configurationService,
                jdbcTemplate,
                objectMapper,
                transactionTemplate
        );

        when(configurationService.getConfiguration()).thenReturn(ConfigurationDto.builder()
                .offlineSyncEnabled(true)
                .build());

        tenantId = UUID.randomUUID();
        TenantContext.setCurrentTenant(tenantId);
    }

    @Test
    void testPushSuccess() {
        SyncPushRequest request = SyncPushRequest.builder()
                .schemaVersion(1)
                .operations(List.of(
                        SyncOperationRequest.builder()
                                .id("op1")
                                .method("POST")
                                .path("/api/v1/tables")
                                .payload(Map.of("name", "Table 5"))
                                .build()
                ))
                .build();

        SyncPushResponse response = syncService.push(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).isSuccess()).isTrue();
        assertThat(response.getResults().get(0).getOperationId()).isEqualTo("op1");
        assertThat(response.getResults().get(0).getStatus()).isEqualTo("SYNCED");

        verify(tableService).saveTable(any());
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    void testPushFailureClassifiedAndStored() {
        // Mock a failure during dispatch
        doThrow(new IllegalArgumentException("Invalid table data"))
                .when(tableService).saveTable(any());

        SyncPushRequest request = SyncPushRequest.builder()
                .schemaVersion(1)
                .operations(List.of(
                        SyncOperationRequest.builder()
                                .id("op1")
                                .method("POST")
                                .path("/api/v1/tables")
                                .payload(Map.of("name", ""))
                                .build()
                ))
                .build();

        SyncPushResponse response = syncService.push(request);

        assertThat(response).isNotNull();
        assertThat(response.getResults()).hasSize(1);
        
        SyncOperationResult result = response.getResults().get(0);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo("FAILED_PERMANENT");
        assertThat(result.getMessage()).isEqualTo("Invalid table data");

        // The save transaction fails, and then a new transaction writes the failure record
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    void testDependsOnSkipped() {
        SyncPushRequest request = SyncPushRequest.builder()
                .schemaVersion(1)
                .operations(List.of(
                        // Failed operation
                        SyncOperationRequest.builder()
                                .id("op1")
                                .method("POST")
                                .path("/api/v1/tables")
                                .payload(Map.of("name", ""))
                                .build(),
                        // Dependent operation
                        SyncOperationRequest.builder()
                                .id("op2")
                                .dependsOnOperationId("op1")
                                .method("POST")
                                .path("/api/v1/tables")
                                .payload(Map.of("name", "Table 6"))
                                .build()
                ))
                .build();

        doThrow(new IllegalArgumentException("Failed"))
                .when(tableService).saveTable(argThat(t -> t != null && "".equals(t.getName())));

        SyncPushResponse response = syncService.push(request);

        assertThat(response.getResults()).hasSize(2);
        assertThat(response.getResults().get(0).isSuccess()).isFalse();
        
        SyncOperationResult depResult = response.getResults().get(1);
        assertThat(depResult.isSuccess()).isFalse();
        assertThat(depResult.getStatus()).isEqualTo("SKIPPED_DEPENDENCY");
        assertThat(depResult.getMessage()).contains("parent operation op1 failed");
    }

    @Test
    void testBootstrapAndChangesGenerateAndDecodeSyncToken() {
        SyncBootstrapResponse bootstrapResp = syncService.bootstrap();
        assertThat(bootstrapResp.getSyncToken()).isNotNull();

        Instant decoded = syncService.decodeSyncToken(bootstrapResp.getSyncToken());
        assertThat(decoded).isNotNull();

        SyncChangesResponse changesResp = syncService.changes(decoded);
        assertThat(changesResp.getSyncToken()).isNotNull();
    }
}
