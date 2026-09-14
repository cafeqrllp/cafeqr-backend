package com.restaurant.pos.table.service;

import com.restaurant.pos.auth.service.EmailService;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.table.domain.RestaurantTable;
import com.restaurant.pos.table.repository.RestaurantTableRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestaurantTableServiceTest {

    private RestaurantTableRepository tableRepository;
    private com.restaurant.pos.order.repository.OrderRepository orderRepository;
    private EmailService emailService;
    private RestaurantTableService tableService;
    private UUID clientId;
    private UUID branchId;

    @BeforeEach
    void setUp() {
        tableRepository = mock(RestaurantTableRepository.class);
        orderRepository = mock(com.restaurant.pos.order.repository.OrderRepository.class);
        emailService = mock(EmailService.class);
        tableService = new RestaurantTableService(tableRepository, orderRepository, emailService, new BranchContextService());
        clientId = UUID.randomUUID();
        branchId = UUID.randomUUID();
        TenantContext.setCurrentTenant(clientId);
        TenantContext.setCurrentOrg(branchId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "owner@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
        ));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void superAdminSelectedBranchReadsOnlySelectedBranchTables() {
        when(tableRepository.findByClientIdAndOrgIdAndIsactiveOrderByDisplayOrderAscTableNumberAsc(clientId, branchId, "Y"))
                .thenReturn(List.of());

        tableService.getActiveTables();

        verify(tableRepository).findByClientIdAndOrgIdAndIsactiveOrderByDisplayOrderAscTableNumberAsc(clientId, branchId, "Y");
        verify(tableRepository, never()).findByClientIdAndIsactiveOrderByDisplayOrderAscTableNumberAsc(eq(clientId), any());
    }

    @Test
    void superAdminAllBranchesReadsAllTables() {
        TenantContext.setCurrentOrg(null);
        when(tableRepository.findByClientIdAndIsactiveOrderByDisplayOrderAscTableNumberAsc(clientId, "Y"))
                .thenReturn(List.of());

        tableService.getActiveTables();

        verify(tableRepository).findByClientIdAndIsactiveOrderByDisplayOrderAscTableNumberAsc(clientId, "Y");
    }

    @Test
    void allBranchesCreateUsesExplicitTargetBranch() {
        TenantContext.setCurrentOrg(null);
        UUID tableId = UUID.randomUUID();
        RestaurantTable input = RestaurantTable.builder()
                .tableNumber("T1")
                .orgId(branchId)
                .build();
        when(tableRepository.save(any(RestaurantTable.class))).thenAnswer(invocation -> {
            RestaurantTable table = invocation.getArgument(0);
            table.setId(tableId);
            return table;
        });
        when(tableRepository.findByIdAndClientId(tableId, clientId)).thenAnswer(invocation -> Optional.of(input));

        RestaurantTable saved = tableService.saveTable(input);

        assertThat(saved.getClientId()).isEqualTo(clientId);
        assertThat(saved.getOrgId()).isEqualTo(branchId);
        verify(tableRepository).save(input);
    }
}
