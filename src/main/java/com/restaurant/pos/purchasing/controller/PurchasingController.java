package com.restaurant.pos.purchasing.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.purchasing.domain.*;
import com.restaurant.pos.purchasing.service.PurchasingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.restaurant.pos.subscription.annotation.RequireModule;
import com.restaurant.pos.subscription.domain.ModuleName;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/purchasing")
@RequiredArgsConstructor
public class PurchasingController {

    private final PurchasingService purchasingService;

    // ═══════════════════════════════════════════════════════════════════════
    // CUSTOMERS
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/customers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.CRM)
    public ResponseEntity<ApiResponse<List<Customer>>> getCustomers() {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.getCustomers()));
    }

    @GetMapping("/customers/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.CRM)
    public ResponseEntity<ApiResponse<Customer>> getCustomer(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.getCustomer(id)));
    }

    @PostMapping("/customers")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.CRM)
    public ResponseEntity<ApiResponse<Customer>> createCustomer(@RequestBody Customer customer) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.saveCustomer(customer)));
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.CRM)
    public ResponseEntity<ApiResponse<Customer>> updateCustomer(@PathVariable UUID id, @RequestBody Customer customer) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.updateCustomer(id, customer)));
    }

    @DeleteMapping("/customers/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.CRM)
    public ResponseEntity<ApiResponse<Void>> deleteCustomer(@PathVariable UUID id) {
        purchasingService.deleteCustomer(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VENDORS
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/vendors")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<List<Vendor>>> getVendors() {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.getVendors()));
    }

    @GetMapping("/vendors/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Vendor>> getVendor(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.getVendor(id)));
    }

    @PostMapping("/vendors")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Vendor>> createVendor(@RequestBody Vendor vendor) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.saveVendor(vendor)));
    }

    @PutMapping("/vendors/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Vendor>> updateVendor(@PathVariable UUID id, @RequestBody Vendor vendor) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.updateVendor(id, vendor)));
    }

    @DeleteMapping("/vendors/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Void>> deleteVendor(@PathVariable UUID id) {
        purchasingService.deleteVendor(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CURRENCIES
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/currencies")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<List<Currency>>> getCurrencies() {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.getCurrencies()));
    }

    @PostMapping("/currencies")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Currency>> createCurrency(@RequestBody Currency currency) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.saveCurrency(currency)));
    }

    @PutMapping("/currencies/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Currency>> updateCurrency(@PathVariable UUID id, @RequestBody Currency currency) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.updateCurrency(id, currency)));
    }

    @DeleteMapping("/currencies/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Void>> deleteCurrency(@PathVariable UUID id) {
        purchasingService.deleteCurrency(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRICELISTS
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/pricelists")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<List<Pricelist>>> getPricelists() {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.getPricelists()));
    }

    @GetMapping("/pricelists/type/{type}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<List<Pricelist>>> getPricelistsByType(@PathVariable String type) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.getPricelistsByType(type.toUpperCase())));
    }

    @PostMapping("/pricelists")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Pricelist>> createPricelist(@RequestBody Pricelist pricelist) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.savePricelist(pricelist)));
    }

    @PutMapping("/pricelists/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Pricelist>> updatePricelist(@PathVariable UUID id, @RequestBody Pricelist pricelist) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.updatePricelist(id, pricelist)));
    }

    @DeleteMapping("/pricelists/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Void>> deletePricelist(@PathVariable UUID id) {
        purchasingService.deletePricelist(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PAYMENT TYPES
    // ═══════════════════════════════════════════════════════════════════════

    @GetMapping("/payment-types")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<List<com.restaurant.pos.purchasing.domain.PaymentType>>> getPaymentTypes(
            @RequestParam(required = false) String applicableFor,
            @RequestParam(required = false) UUID orgId) {
        List<com.restaurant.pos.purchasing.domain.PaymentType> result =
                (applicableFor != null && !applicableFor.isBlank())
                        ? purchasingService.getPaymentTypesByApplicableFor(applicableFor, orgId)
                        : purchasingService.getPaymentTypes(orgId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/payment-types")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<com.restaurant.pos.purchasing.domain.PaymentType>> createPaymentType(
            @RequestBody com.restaurant.pos.purchasing.domain.PaymentType paymentType) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.savePaymentType(paymentType)));
    }

    @PutMapping("/payment-types/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<com.restaurant.pos.purchasing.domain.PaymentType>> updatePaymentType(
            @PathVariable UUID id,
            @RequestBody com.restaurant.pos.purchasing.domain.PaymentType paymentType) {
        return ResponseEntity.ok(ApiResponse.success(purchasingService.updatePaymentType(id, paymentType)));
    }

    @DeleteMapping("/payment-types/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    @RequireModule(ModuleName.INVENTORY)
    public ResponseEntity<ApiResponse<Void>> deletePaymentType(@PathVariable UUID id) {
        purchasingService.deletePaymentType(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}

