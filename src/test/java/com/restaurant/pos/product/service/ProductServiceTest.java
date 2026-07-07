package com.restaurant.pos.product.service;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.repository.CategoryRepository;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.product.repository.UomRepository;
import com.restaurant.pos.product.repository.VariantGroupRepository;
import com.restaurant.pos.product.repository.VariantOptionRepository;
import com.restaurant.pos.purchasing.repository.PricelistRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductServiceTest {

    private ProductRepository productRepository;
    private CategoryRepository categoryRepository;
    private ProductService productService;
    private UUID clientId;
    private UUID branchId;
    private UUID otherBranchId;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        productService = new ProductService(
                productRepository,
                categoryRepository,
                mock(UomRepository.class),
                mock(VariantGroupRepository.class),
                mock(VariantOptionRepository.class),
                mock(PricelistRepository.class)
        );
        clientId = UUID.randomUUID();
        branchId = UUID.randomUUID();
        otherBranchId = UUID.randomUUID();
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
    void superAdminAllBranchesCanReadBranchOwnedProduct() {
        TenantContext.setCurrentOrg(null);
        UUID productId = UUID.randomUUID();
        Product product = branchProduct(productId, branchId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        assertThat(productService.getProduct(productId).getId()).isEqualTo(productId);
    }

    @Test
    void superAdminSelectedBranchCannotReadAnotherBranchProduct() {
        UUID productId = UUID.randomUUID();
        Product product = branchProduct(productId, otherBranchId);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> productService.getProduct(productId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("another organization");
    }

    @Test
    void allBranchesUpdatePreservesBranchOwnedProductOrg() {
        TenantContext.setCurrentOrg(null);
        UUID productId = UUID.randomUUID();
        Product existing = branchProduct(productId, branchId);
        Product request = Product.builder()
                .name("Updated")
                .price(new BigDecimal("20.00"))
                .category(existing.getCategory())
                .isActive(true)
                .isAvailable(true)
                .build();
        when(productRepository.findById(productId)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(existing.getCategory().getId())).thenReturn(Optional.of(existing.getCategory()));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Product saved = productService.updateProduct(productId, request);

        assertThat(saved.getOrgId()).isEqualTo(branchId);
        assertThat(saved.getName()).isEqualTo("Updated");
    }

    private Product branchProduct(UUID productId, UUID ownerBranchId) {
        com.restaurant.pos.product.domain.Category category = com.restaurant.pos.product.domain.Category.builder()
                .id(UUID.randomUUID())
                .name("Food")
                .build();
        category.setClientId(clientId);
        Product product = Product.builder()
                .id(productId)
                .name("Biryani")
                .price(new BigDecimal("10.00"))
                .category(category)
                .isActive(true)
                .isAvailable(true)
                .build();
        product.setClientId(clientId);
        product.setOrgId(ownerBranchId);
        return product;
    }
}
