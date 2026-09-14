package com.restaurant.pos.table.service;

import com.restaurant.pos.auth.service.EmailService;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.service.BranchContextService;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.table.domain.RestaurantTable;
import com.restaurant.pos.table.repository.RestaurantTableRepository;
import com.restaurant.pos.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RestaurantTableService {

    private final RestaurantTableRepository tableRepository;
    private final OrderRepository orderRepository;
    private final EmailService emailService;
    private final BranchContextService branchContext;

    @org.springframework.beans.factory.annotation.Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Transactional
    public List<RestaurantTable> getAllTables() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        List<RestaurantTable> tables;
        if (orgId == null) {
            tables = tableRepository.findByClientIdOrderByDisplayOrderAscTableNumberAsc(clientId);
        } else {
            tables = tableRepository.findByClientIdAndOrgIdOrderByDisplayOrderAscTableNumberAsc(clientId, orgId);
        }
        return reconcileTableStatuses(tables, clientId);
    }

    @Transactional
    public List<RestaurantTable> getActiveTables() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        List<RestaurantTable> tables;
        if (orgId == null) {
            tables = tableRepository.findByClientIdAndIsactiveOrderByDisplayOrderAscTableNumberAsc(clientId, "Y");
        } else {
            tables = tableRepository.findByClientIdAndOrgIdAndIsactiveOrderByDisplayOrderAscTableNumberAsc(clientId, orgId, "Y");
        }
        return reconcileTableStatuses(tables, clientId);
    }

    private List<RestaurantTable> reconcileTableStatuses(List<RestaurantTable> tables, UUID clientId) {
        if (tables == null || tables.isEmpty()) return tables;
        for (RestaurantTable table : tables) {
            String status = String.valueOf(table.getStatus()).toUpperCase();
            if ("OCCUPIED".equals(status) || "BILLED".equals(status)) {
                boolean hasLiveOrder = orderRepository.existsLiveOrderByTable(clientId, table.getOrgId(), table.getId(), table.getTableNumber());
                if (!hasLiveOrder) {
                    table.setStatus("AVAILABLE");
                    tableRepository.save(table);
                }
            }
        }
        return tables;
    }

    @Transactional(readOnly = true)
    public RestaurantTable getTable(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = branchContext.getReadOrgId(null);
        if (orgId == null) {
            return tableRepository.findByIdAndClientId(id, clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Table not found"));
        }
        return tableRepository.findByIdAndClientIdAndOrgId(id, clientId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Table not found"));
    }

    @Transactional(readOnly = true)
    public List<RestaurantTable> getTablesChangedSince(Instant since) {
        if (since == null) {
            return getAllTables();
        }
        UUID clientId = TenantContext.getCurrentTenant();
        LocalDateTime updatedAfter = LocalDateTime.ofInstant(since, ZoneOffset.UTC);
        UUID orgId = branchContext.getReadOrgId(null);
        if (orgId == null) {
            return tableRepository.findByClientIdAndUpdatedAtAfterOrderByDisplayOrderAscTableNumberAsc(clientId, updatedAfter);
        }
        return tableRepository.findByClientIdAndOrgIdAndUpdatedAtAfterOrderByDisplayOrderAscTableNumberAsc(
                clientId,
                orgId,
                updatedAfter
        );
    }

    @Transactional
    public RestaurantTable saveTable(RestaurantTable table) {
        boolean isNew = table.getId() == null;
        if (!isNew) {
            RestaurantTable existing = getTable(table.getId());
            copyMutableFields(existing, table);
            return tableRepository.save(existing);
        }

        table.setClientId(TenantContext.getCurrentTenant());
        table.setOrgId(branchContext.requireWriteOrgId(table.getOrgId()));
        RestaurantTable saved = tableRepository.save(table);
        
        // On creation, automatically send QR mail to owner
        if (isNew) {
            String qrLink = String.format("%s/menu/%s/%s/%s", frontendUrl, saved.getClientId(), saved.getOrgId(), saved.getId());
            sendQRCode(saved.getId(), null, qrLink);
        }
        
        return saved;
    }

    private void copyMutableFields(RestaurantTable target, RestaurantTable source) {
        target.setTableNumber(source.getTableNumber());
        target.setName(source.getName());
        target.setSeatingCapacity(source.getSeatingCapacity());
        target.setFloor(source.getFloor());
        target.setSection(source.getSection());
        target.setShape(source.getShape());
        target.setStatus(source.getStatus());
        target.setNotes(source.getNotes());
        target.setDisplayOrder(source.getDisplayOrder());
        if (source.getIsactive() != null) {
            target.setIsactive(source.getIsactive());
        }
    }

    @Transactional
    public RestaurantTable updateTableStatus(UUID id, String status) {
        RestaurantTable table = getTable(id);
        table.setStatus(status);
        return tableRepository.save(table);
    }

    @Transactional
    public void deleteTable(UUID id) {
        RestaurantTable table = getTable(id);
        
        // Validation: Ensure table is not "Used"
        if ("OCCUPIED".equalsIgnoreCase(table.getStatus())
                || "BILLED".equalsIgnoreCase(table.getStatus())
                || "RESERVED".equalsIgnoreCase(table.getStatus())) {
            throw new IllegalStateException("Cannot delete Table " + table.getTableNumber() + " because it is currently " + table.getStatus());
        }

        // Soft Delete
        table.setIsactive("N");
        tableRepository.save(table);
    }

    public void sendQRCode(UUID id, String targetEmail, String qrLink) {
        RestaurantTable table = getTable(id);
        String email = (targetEmail != null && !targetEmail.isEmpty()) 
                       ? targetEmail 
                       : SecurityUtils.getCurrentUserEmail();
        
        if (email != null && !email.isEmpty()) {
            emailService.sendTableQREmail(email, table.getTableNumber(), qrLink);
        }
    }
}
