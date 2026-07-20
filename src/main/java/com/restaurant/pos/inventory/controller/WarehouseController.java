package com.restaurant.pos.inventory.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.inventory.domain.Warehouse;
import com.restaurant.pos.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/warehouses")
@RequiredArgsConstructor
@com.restaurant.pos.subscription.annotation.RequireModule(com.restaurant.pos.subscription.domain.ModuleName.INVENTORY)
public class WarehouseController {

    private final InventoryService inventoryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<Warehouse>>> getWarehouses(@RequestParam(required = false) UUID orgId) {
        return ResponseEntity.ok(ApiResponse.success(inventoryService.getWarehouses(orgId)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<Warehouse>> getWarehouse(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(inventoryService.getWarehouse(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<Warehouse>> createWarehouse(@RequestBody Warehouse warehouse) {
        return ResponseEntity.ok(ApiResponse.success(inventoryService.saveWarehouse(warehouse)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<Warehouse>> updateWarehouse(@PathVariable UUID id, @RequestBody Warehouse warehouse) {
        warehouse.setId(id);
        return ResponseEntity.ok(ApiResponse.success(inventoryService.saveWarehouse(warehouse)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<Void>> deleteWarehouse(@PathVariable UUID id) {
        inventoryService.deleteWarehouse(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
