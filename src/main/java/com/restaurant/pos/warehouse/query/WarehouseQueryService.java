package com.restaurant.pos.warehouse.query;

import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.warehouse.domain.Warehouse;
import com.restaurant.pos.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CQRS Query Service — handles all read-side operations for Warehouse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseQueryService {

    private final WarehouseRepository warehouseRepository;

    /**
     * Returns all warehouses visible to the current tenant/branch.
     * Super-admins with no explicit orgId filter see all warehouses for the client.
     * Result is cached in Redis under 'warehouses_v1' for 6 hours.
     */
    @Transactional(readOnly = true)
    public List<Warehouse> getWarehouses(UUID orgId, boolean all) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID effectiveOrgId = orgId != null ? orgId : TenantContext.getCurrentOrg();

        List<Warehouse> list;
        if (all || (SecurityUtils.isSuperAdmin() && orgId == null)) {
            list = warehouseRepository.findByClientIdOrderByCreatedAtDesc(clientId);
        } else {
            list = warehouseRepository.findByClientIdAndOrgIdOrGlobalOrderByCreatedAtDesc(clientId, effectiveOrgId);
        }

        // Auto-promote the sole org warehouse to default when it is not yet flagged
        if (effectiveOrgId != null && !all) {
            List<Warehouse> orgWhs = list.stream()
                    .filter(w -> effectiveOrgId.equals(w.getOrgId()))
                    .toList();
            if (orgWhs.size() == 1 && !orgWhs.get(0).isDefault()) {
                Warehouse single = orgWhs.get(0);
                single.setDefault(true);
                warehouseRepository.save(single);
            }
        }

        return list;
    }

    public List<Warehouse> getWarehouses(UUID orgId) {
        return getWarehouses(orgId, false);
    }

    /** Convenience overload — uses the branch from the current security context. */
    @Transactional(readOnly = true)
    public List<Warehouse> getWarehouses() {
        return getWarehouses(null);
    }

    /**
     * Returns a single Warehouse by ID, scoped to the current tenant.
     * Super-admins may access any warehouse belonging to the client.
     */
    @Transactional(readOnly = true)
    public Warehouse getWarehouse(UUID id) {
        UUID clientId = TenantContext.getCurrentTenant();
        if (SecurityUtils.isSuperAdmin()) {
            return warehouseRepository.findByIdAndClientId(id, clientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found"));
        }
        return warehouseRepository.findByIdAndClientIdAndOrgIdOrGlobal(id, clientId, TenantContext.getCurrentOrg())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found for ID: " + id));
    }

    /**
     * Resolves the default warehouse for a given client + org.
     * Falls back to any org warehouse, then to the client-level default.
     * Used by OrderService and InventoryService to resolve stock-tracking warehouse.
     */
    @Transactional(readOnly = true)
    public Optional<Warehouse> findDefaultWarehouse(UUID clientId, UUID orgId) {
        if (orgId != null) {
            List<Warehouse> defaults = warehouseRepository.findDefaultWarehousesForOrg(clientId, orgId);
            if (!defaults.isEmpty()) {
                return Optional.of(defaults.get(0));
            }
            List<Warehouse> orgWarehouses = warehouseRepository.findByClientIdAndOrgIdOrderByCreatedAtDesc(clientId, orgId);
            if (!orgWarehouses.isEmpty()) {
                return Optional.of(orgWarehouses.get(0));
            }
        }
        return warehouseRepository.findFirstByClientIdAndIsDefaultTrue(clientId);
    }
}
