package com.restaurant.pos.product.controller;

import com.restaurant.pos.common.dto.ApiResponse;
import com.restaurant.pos.product.domain.Category;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.domain.Uom;
import com.restaurant.pos.product.domain.VariantGroup;
import com.restaurant.pos.product.domain.VariantOption;
import com.restaurant.pos.product.dto.ProductDetailDto;
import com.restaurant.pos.product.dto.ProductListDto;
import com.restaurant.pos.product.dto.VariantGroupDto;
import com.restaurant.pos.product.dto.VariantOptionDto;
import com.restaurant.pos.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<ProductListDto>>> getProducts() {
        System.out.println("===> [DEBUG] ProductController: Request received for getProducts");
        List<ProductListDto> products = productService.getProducts();
        System.out.println("===> [DEBUG] ProductController: Returning " + products.size() + " products");
        return ResponseEntity.ok(ApiResponse.success(products));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<ProductDetailDto>> getProduct(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.success(productService.getProduct(id)));
    }

    @GetMapping("/barcode/{barcode}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<ProductDetailDto>> getProductByBarcode(@PathVariable String barcode) {
        return ResponseEntity.ok(ApiResponse.success(productService.getProductByBarcode(barcode)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<Product>> createProduct(@RequestBody Product product) {
        return ResponseEntity.ok(ApiResponse.success(productService.createProduct(product)));
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<List<Product>>> bulkCreateProducts(@RequestBody List<Product> products) {
        return ResponseEntity.ok(ApiResponse.success(productService.bulkCreateProducts(products)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Product>> updateProduct(@PathVariable UUID id, @RequestBody Product product) {
        return ResponseEntity.ok(ApiResponse.success(productService.updateProduct(id, product)));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Product>> updateProductStatus(@PathVariable UUID id, @RequestBody Map<String, Boolean> request) {
        Boolean requestedStatus = request.containsKey("isActive") ? request.get("isActive") : request.get("isAvailable");
        boolean active = Boolean.TRUE.equals(requestedStatus);
        return ResponseEntity.ok(ApiResponse.success(productService.updateProductStatus(id, active)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteProduct(@PathVariable UUID id) {
        productService.deleteProduct(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<Category>>> getCategories() {
        return ResponseEntity.ok(ApiResponse.success(productService.getCategories()));
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Category>> createCategory(@RequestBody Category category) {
        return ResponseEntity.ok(ApiResponse.success(productService.createCategory(category)));
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Category>> updateCategory(@PathVariable UUID id, @RequestBody Category category) {
        return ResponseEntity.ok(ApiResponse.success(productService.updateCategory(id, category)));
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteCategory(@PathVariable UUID id) {
        productService.deleteCategory(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // --- UOM Endpoints ---

    @GetMapping("/uoms")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<Uom>>> getUoms() {
        return ResponseEntity.ok(ApiResponse.success(productService.getUoms()));
    }

    @PostMapping("/uoms")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Uom>> createUom(@RequestBody Uom uom) {
        return ResponseEntity.ok(ApiResponse.success(productService.createUom(uom)));
    }

    @PutMapping("/uoms/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Uom>> updateUom(@PathVariable UUID id, @RequestBody Uom uom) {
        return ResponseEntity.ok(ApiResponse.success(productService.updateUom(id, uom)));
    }

    @DeleteMapping("/uoms/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteUom(@PathVariable UUID id) {
        productService.deleteUom(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // --- Variant Endpoints ---

    @GetMapping("/variants/groups")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<VariantGroupDto>>> getVariantGroups() {
        return ResponseEntity.ok(ApiResponse.success(productService.getVariantGroups()));
    }

    @PostMapping("/variants/groups")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<VariantGroupDto>> createVariantGroup(@RequestBody VariantGroup group) {
        return ResponseEntity.ok(ApiResponse.success(productService.createVariantGroup(group)));
    }

    @PutMapping("/variants/groups/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<VariantGroupDto>> updateVariantGroup(@PathVariable UUID id, @RequestBody VariantGroup group) {
        return ResponseEntity.ok(ApiResponse.success(productService.updateVariantGroup(id, group)));
    }

    @DeleteMapping("/variants/groups/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteVariantGroup(@PathVariable UUID id) {
        productService.deleteVariantGroup(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/variants/groups/{groupId}/options")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'STAFF')")
    public ResponseEntity<ApiResponse<List<VariantOptionDto>>> getVariantOptions(@PathVariable UUID groupId) {
        return ResponseEntity.ok(ApiResponse.success(productService.getVariantOptionsByGroup(groupId)));
    }

    @PostMapping("/variants/options")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<VariantOptionDto>> createVariantOption(@RequestBody VariantOption option) {
        return ResponseEntity.ok(ApiResponse.success(productService.createVariantOption(option)));
    }

    @PutMapping("/variants/options/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<VariantOptionDto>> updateVariantOption(@PathVariable UUID id, @RequestBody VariantOption option) {
        return ResponseEntity.ok(ApiResponse.success(productService.updateVariantOption(id, option)));
    }

    @DeleteMapping("/variants/options/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteVariantOption(@PathVariable UUID id) {
        productService.deleteVariantOption(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
