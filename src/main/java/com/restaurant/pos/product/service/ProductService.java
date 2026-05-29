package com.restaurant.pos.product.service;

import com.restaurant.pos.common.exception.BusinessException;
import com.restaurant.pos.common.tenant.TenantContext;
import com.restaurant.pos.common.exception.ResourceNotFoundException;
import com.restaurant.pos.common.util.SecurityUtils;
import com.restaurant.pos.product.domain.Category;
import com.restaurant.pos.product.domain.Product;
import com.restaurant.pos.product.domain.ProductRecipe;
import com.restaurant.pos.product.domain.Uom;
import com.restaurant.pos.product.domain.VariantGroup;
import com.restaurant.pos.product.domain.VariantOption;
import com.restaurant.pos.product.repository.CategoryRepository;
import com.restaurant.pos.product.repository.ProductRepository;
import com.restaurant.pos.product.repository.UomRepository;
import com.restaurant.pos.product.repository.VariantGroupRepository;
import com.restaurant.pos.product.repository.VariantOptionRepository;
import com.restaurant.pos.product.dto.ProductDetailDto;
import com.restaurant.pos.product.dto.ProductListDto;
import com.restaurant.pos.product.dto.VariantGroupDto;
import com.restaurant.pos.product.dto.VariantOptionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final UomRepository uomRepository;
    private final VariantGroupRepository variantGroupRepository;
    private final VariantOptionRepository variantOptionRepository;

    @Transactional(readOnly = true)
    // @Cacheable(value = "products_categories_v2", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public List<Category> getCategories() {
        return categoryRepository.findByClientIdAndOrgIdOrGlobal(TenantContext.getCurrentTenant(),
                TenantContext.getCurrentOrg());
    }

    @Transactional
    @CacheEvict(value = "products_categories_v2", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public Category createCategory(Category category) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        // Duplicate Check
        if (categoryRepository.findByNameAndClientIdAndOrgIdOrGlobal(category.getName(), clientId, orgId).isPresent()) {
            throw new BusinessException("Category with this name already exists");
        }

        category.setClientId(clientId);
        category.setOrgId(orgId);
        return categoryRepository.save(category);
    }

    @Transactional
    @CacheEvict(value = "products_categories_v2", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public Category updateCategory(UUID id, Category category) {
        Category existing = categoryRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        validateOwnership(existing.getClientId(), existing.getOrgId(), "Category");

        existing.setName(category.getName());
        existing.setDescription(category.getDescription());
        existing.setActive(category.isActive());
        existing.setImageUrl(category.getImageUrl());
        return categoryRepository.save(existing);
    }

    @Transactional
    @CacheEvict(value = "products_categories_v2", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public void deleteCategory(UUID id) {
        Category category = categoryRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));

        validateOwnership(category.getClientId(), category.getOrgId(), "Category");

        category.setActive(false);
        categoryRepository.save(category);
    }

    // --- UOM Methods ---

    @Transactional(readOnly = true)
    @Cacheable(value = "products_uoms_v2", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public List<Uom> getUoms() {
        return uomRepository.findByClientIdAndOrgIdOrGlobal(TenantContext.getCurrentTenant(),
                TenantContext.getCurrentOrg());
    }

    @Transactional
    @CacheEvict(value = "products_uoms_v2", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public Uom createUom(Uom uom) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        if (uomRepository.findByNameAndClientIdAndOrgIdOrGlobal(uom.getName(), clientId, orgId).isPresent()) {
            throw new BusinessException("UOM with this name already exists");
        }

        uom.setClientId(clientId);
        uom.setOrgId(orgId);
        return uomRepository.save(uom);
    }

    @Transactional
    @CacheEvict(value = "products_uoms_v2", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public Uom updateUom(UUID id, Uom uom) {
        Uom existing = uomRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("UOM not found"));

        validateOwnership(existing.getClientId(), existing.getOrgId(), "UOM");

        existing.setName(uom.getName());
        existing.setShortName(uom.getShortName());
        existing.setActive(uom.isActive());
        existing.setDefault(uom.isDefault());
        existing.setUomPrecision(uom.getUomPrecision());
        return uomRepository.save(existing);
    }

    @Transactional
    @CacheEvict(value = "products_uoms_v2", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public void deleteUom(UUID id) {
        Uom uom = uomRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("UOM not found"));

        validateOwnership(uom.getClientId(), uom.getOrgId(), "UOM");

        uom.setActive(false);
        uomRepository.save(uom);
    }

    // --- Variant Methods ---

    @Transactional(readOnly = true)
    public List<VariantGroupDto> getVariantGroups() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        List<VariantGroupDto> groups = variantGroupRepository.findByClientIdAndOrgIdOrGlobal(clientId, orgId)
                .stream()
                .map(this::mapVariantGroupToDto)
                .collect(Collectors.toList());
        log.info("Loaded variant groups. clientId={}, orgId={}, count={}", clientId, orgId, groups.size());
        return groups;
    }

    @Transactional(readOnly = true)
    public List<VariantGroupDto> getVariantGroupsChangedSince(Instant since) {
        if (since == null) {
            return getVariantGroups();
        }
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        LocalDateTime updatedAfter = LocalDateTime.ofInstant(since, ZoneOffset.UTC);
        return variantGroupRepository.findChangedByClientIdAndOrgIdOrGlobal(clientId, orgId, updatedAfter)
                .stream()
                .map(this::mapVariantGroupToDto)
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheEvict(value = { "products_variant_groups_v2", "variant_options" }, allEntries = true)
    public VariantGroupDto createVariantGroup(VariantGroup group) {
        group.setClientId(TenantContext.getCurrentTenant());
        group.setOrgId(TenantContext.getCurrentOrg());
        
        if (group.getOptions() != null) {
            group.getOptions().forEach(opt -> {
                opt.setGroup(group);
                opt.setClientId(group.getClientId());
                opt.setOrgId(group.getOrgId());
            });
        }
        return mapVariantGroupToDto(variantGroupRepository.save(group));
    }

    @Transactional
    @CacheEvict(value = { "products_variant_groups_v2", "variant_options" }, allEntries = true)
    public VariantGroupDto updateVariantGroup(UUID id, VariantGroup group) {
        VariantGroup existing = variantGroupRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Variant Group not found"));

        validateOwnership(existing.getClientId(), existing.getOrgId(), "Variant Group");

        if (existing.isActive() && !group.isActive()) {
            if (productRepository.existsByVariantMappings_VariantGroup_IdAndIsActiveTrue(id)) {
                throw new BusinessException("Cannot deactivate variant group because it is currently used by active products");
            }
        }

        existing.setName(group.getName());
        existing.setActive(group.isActive());

        // Update Cascade Options
        existing.getOptions().clear();
        if (group.getOptions() != null) {
            group.getOptions().forEach(opt -> {
                opt.setGroup(existing);
                opt.setClientId(existing.getClientId());
                opt.setOrgId(existing.getOrgId());
                existing.getOptions().add(opt);
            });
        }
        return mapVariantGroupToDto(variantGroupRepository.save(existing));
    }

    @Transactional
    @CacheEvict(value = { "products_variant_groups_v2", "variant_options" }, allEntries = true)
    public void deleteVariantGroup(UUID id) {
        VariantGroup group = variantGroupRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Variant Group not found"));

        validateOwnership(group.getClientId(), group.getOrgId(), "Variant Group");

        group.setActive(false);
        variantGroupRepository.save(group);
    }

    @Transactional(readOnly = true)
    public List<VariantOptionDto> getVariantOptionsByGroup(UUID groupId) {
        VariantGroup group = variantGroupRepository.findById(java.util.Objects.requireNonNull(groupId))
                .orElseThrow(() -> new ResourceNotFoundException("Variant Group not found"));
        validateOwnership(group.getClientId(), group.getOrgId(), "Variant Group", false);
        return variantOptionRepository.findByGroup_Id(groupId)
                .stream()
                .map(option -> mapVariantOptionToDto(option, groupId))
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheEvict(value = { "products_variant_groups_v2", "variant_options" }, allEntries = true)
    public VariantOptionDto createVariantOption(VariantOption option) {
        if (option.getGroup() == null || option.getGroup().getId() == null) {
            throw new BusinessException("Variant Group ID is required");
        }

        VariantGroup group = variantGroupRepository.findById(java.util.Objects.requireNonNull(option.getGroup().getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Variant Group not found"));

        validateOwnership(group.getClientId(), group.getOrgId(), "Variant Group");

        option.setGroup(group);
        option.setClientId(TenantContext.getCurrentTenant());
        option.setOrgId(effectiveWriteOrgId(group.getOrgId()));
        return mapVariantOptionToDto(variantOptionRepository.save(option));
    }

    @Transactional
    @CacheEvict(value = { "products_variant_groups_v2", "variant_options" }, allEntries = true)
    public VariantOptionDto updateVariantOption(UUID id, VariantOption option) {
        VariantOption existing = variantOptionRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Variant Option not found"));

        validateOwnership(existing.getClientId(), existing.getOrgId(), "Variant Option");

        if (existing.isActive() && !option.isActive()) {
            if (productRepository.existsByVariantPricings_VariantOption_IdAndIsActiveTrue(id)) {
                throw new BusinessException("Cannot deactivate variant option because it is currently used by active products");
            }
        }

        existing.setName(option.getName());
        existing.setAdditionalPrice(option.getAdditionalPrice());
        existing.setActive(option.isActive());
        return mapVariantOptionToDto(variantOptionRepository.save(existing));
    }

    @Transactional
    @CacheEvict(value = { "products_variant_groups_v2", "variant_options" }, allEntries = true)
    public void deleteVariantOption(UUID id) {
        VariantOption option = variantOptionRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Variant Option not found"));

        validateOwnership(option.getClientId(), option.getOrgId(), "Variant Option");

        option.setActive(false);
        variantOptionRepository.save(option);
    }

    // --- Product Methods ---

    @Transactional(readOnly = true)
    // @Cacheable(value = "products_list_v4", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public List<ProductListDto> getProducts() {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        System.out.println("===> [DEBUG] ProductService: Fetching products for Client: " + clientId + " | Org: " + orgId);
        List<Product> products = productRepository.findByClientIdAndOrgIdOrGlobal(clientId, orgId);
        System.out.println("===> [DEBUG] ProductService: Repository returned " + products.size() + " products");

        return products.stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ProductListDto> getProductsChangedSince(Instant since) {
        if (since == null) {
            return getProducts();
        }
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        LocalDateTime updatedAfter = LocalDateTime.ofInstant(since, ZoneOffset.UTC);
        return productRepository.findChangedByClientIdAndOrgIdOrGlobal(clientId, orgId, updatedAfter)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Category> getCategoriesChangedSince(Instant since) {
        if (since == null) {
            return getCategories();
        }
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        LocalDateTime updatedAfter = LocalDateTime.ofInstant(since, ZoneOffset.UTC);
        return categoryRepository.findChangedByClientIdAndOrgIdOrGlobal(clientId, orgId, updatedAfter);
    }

    @Transactional(readOnly = true)
    public List<Uom> getUomsChangedSince(Instant since) {
        if (since == null) {
            return getUoms();
        }
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();
        LocalDateTime updatedAfter = LocalDateTime.ofInstant(since, ZoneOffset.UTC);
        return uomRepository.findChangedByClientIdAndOrgIdOrGlobal(clientId, orgId, updatedAfter);
    }

    @Transactional(readOnly = true)
    public ProductDetailDto getProduct(UUID id) {
        Product product = productRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        validateOwnership(product.getClientId(), product.getOrgId(), "Product", false);
        return mapToDetailDto(product);
    }

    private void initializeProductDetails(Product product) {
        if (product.getCategory() != null) product.getCategory().getName();
        if (product.getUom() != null) product.getUom().getName();

        if (product.getVariantMappings() != null) {
            product.getVariantMappings().forEach(mapping -> {
                if (mapping.getVariantGroup() != null) {
                    mapping.getVariantGroup().getName();
                    mapping.getVariantGroup().getOptions().size();
                }
            });
        }

        if (product.getVariantPricings() != null) {
            product.getVariantPricings().forEach(pricing -> {
                if (pricing.getVariantOption() != null) {
                    pricing.getVariantOption().getName();
                }
            });
        }

        if (product.getUpsells() != null) {
            product.getUpsells().forEach(upsell -> {
                if (upsell.getUpsellProduct() != null) {
                    upsell.getUpsellProduct().getName();
                }
            });
        }
    }

    private ProductListDto mapToDto(Product product) {
        try {
            return ProductListDto.builder()
                    .id(product.getId())
                    .name(product.getName())
                    .description(product.getDescription())
                    .price(product.getPrice())
                    .isAvailable(product.isAvailable())
                    .imageUrl(product.getImageUrl())
                    .categoryId(product.getCategory() != null ? product.getCategory().getId() : null)
                    .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                    .uomId(product.getUom() != null ? product.getUom().getId() : null)
                    .uomName(product.getUom() != null ? product.getUom().getName() : null)
                    .productCode(product.getProductCode())
                    .taxRate(product.getTaxRate())
                    .taxCode(product.getTaxCode())
                    .isActive(product.isActive())
                    .isPackagedGood(product.isPackagedGood())
                    .isIngredient(product.isIngredient())
                    .productType(product.getProductType())
                    .hasVariants(product.getVariantMappings() != null && !product.getVariantMappings().isEmpty())
                    .variantCount(product.getVariantMappings() != null ? product.getVariantMappings().size() : 0)
                    .hasUpsells(product.getUpsells() != null && !product.getUpsells().isEmpty())
                    .upsellCount(product.getUpsells() != null ? product.getUpsells().size() : 0)
                    .hasIngredients(product.getRecipeLines() != null && !product.getRecipeLines().isEmpty())
                    .defaultPricelistId(product.getDefaultPricelist() != null ? product.getDefaultPricelist().getId() : null)
                    .defaultPricelistName(product.getDefaultPricelist() != null ? product.getDefaultPricelist().getName() : null)
                    .build();
        } catch (Exception e) {
            System.err.println("===> [ERROR] mapToDto failed for product: " + product.getId() + " - " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    private ProductDetailDto mapToDetailDto(Product product) {
        ProductDetailDto.CategorySummary category = product.getCategory() == null
                ? null
                : ProductDetailDto.CategorySummary.builder()
                        .id(product.getCategory().getId())
                        .name(product.getCategory().getName())
                        .build();

        ProductDetailDto.UomSummary uom = product.getUom() == null
                ? null
                : ProductDetailDto.UomSummary.builder()
                        .id(product.getUom().getId())
                        .name(product.getUom().getName())
                        .shortName(product.getUom().getShortName())
                        .build();

        List<ProductDetailDto.VariantMappingDto> mappings = product.getVariantMappings() == null
                ? List.of()
                : product.getVariantMappings().stream()
                        .map(mapping -> ProductDetailDto.VariantMappingDto.builder()
                                .id(mapping.getId())
                                .isRequired(mapping.isRequired())
                                .variantGroup(mapping.getVariantGroup() == null ? null
                                        : mapVariantGroupToDto(mapping.getVariantGroup()))
                                .build())
                        .collect(Collectors.toList());

        List<ProductDetailDto.VariantPricingDto> pricings = product.getVariantPricings() == null
                ? List.of()
                : product.getVariantPricings().stream()
                        .map(pricing -> ProductDetailDto.VariantPricingDto.builder()
                                .id(pricing.getId())
                                .overridePrice(pricing.getOverridePrice())
                                .isAvailable(pricing.isAvailable())
                                .variantOption(pricing.getVariantOption() == null ? null
                                        : mapVariantOptionToDto(pricing.getVariantOption()))
                                .build())
                        .collect(Collectors.toList());

        List<ProductDetailDto.UpsellDto> upsells = product.getUpsells() == null
                ? List.of()
                : product.getUpsells().stream()
                        .map(upsell -> ProductDetailDto.UpsellDto.builder()
                                .id(upsell.getId())
                                .isActive(upsell.isActive())
                                .upsellProduct(upsell.getUpsellProduct() == null ? null
                                        : ProductDetailDto.ProductSummary.builder()
                                                .id(upsell.getUpsellProduct().getId())
                                                .name(upsell.getUpsellProduct().getName())
                                                .price(upsell.getUpsellProduct().getPrice())
                                                .build())
                                .build())
                        .collect(Collectors.toList());

        List<ProductDetailDto.RecipeDto> recipeLines = product.getRecipeLines() == null
                ? List.of()
                : product.getRecipeLines().stream()
                        .map(recipe -> ProductDetailDto.RecipeDto.builder()
                                .id(recipe.getId())
                                .ingredientId(recipe.getIngredient() != null ? recipe.getIngredient().getId() : null)
                                .ingredientName(recipe.getIngredient() != null ? recipe.getIngredient().getName() : null)
                                .quantity(recipe.getQuantity())
                                .uomName(recipe.getIngredient() != null && recipe.getIngredient().getUom() != null
                                        ? recipe.getIngredient().getUom().getName() : null)
                                .isActive(recipe.isActive())
                                .build())
                        .collect(Collectors.toList());

        return ProductDetailDto.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .isAvailable(product.isAvailable())
                .imageUrl(product.getImageUrl())
                .productType(product.getProductType())
                .isVariant(product.isVariant())
                .isPackagedGood(product.isPackagedGood())
                .isIngredient(product.isIngredient())
                .productCode(product.getProductCode())
                .taxRate(product.getTaxRate())
                .taxCode(product.getTaxCode())
                .mrp(product.getMrp())
                .costPrice(product.getCostPrice())
                .barcode(product.getBarcode())
                .minStockLevel(product.getMinStockLevel())
                .kdsStation(product.getKdsStation())
                .isActive(product.isActive())
                .category(category)
                .uom(uom)
                .variantMappings(mappings)
                .variantPricings(pricings)
                .upsells(upsells)
                .recipeLines(recipeLines)
                .build();
    }

    private VariantGroupDto mapVariantGroupToDto(VariantGroup group) {
        List<VariantOptionDto> options = group.getOptions() == null
                ? List.of()
                : group.getOptions().stream()
                        .map(option -> mapVariantOptionToDto(option, group.getId()))
                        .collect(Collectors.toList());

        return VariantGroupDto.builder()
                .id(group.getId())
                .name(group.getName())
                .isActive(group.isActive())
                .options(options)
                .build();
    }

    private VariantOptionDto mapVariantOptionToDto(VariantOption option) {
        UUID groupId = option.getGroup() != null ? option.getGroup().getId() : null;
        return mapVariantOptionToDto(option, groupId);
    }

    private VariantOptionDto mapVariantOptionToDto(VariantOption option, UUID groupId) {
        return VariantOptionDto.builder()
                .id(option.getId())
                .groupId(groupId)
                .name(option.getName())
                .additionalPrice(option.getAdditionalPrice())
                .isActive(option.isActive())
                .build();
    }



    @Transactional
    @CacheEvict(value = "products_list_v3", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public Product createProduct(Product product) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        // Nullify empty codes
        if (product.getProductCode() != null && product.getProductCode().trim().isEmpty()) {
            product.setProductCode(null);
        }

        // Deep Validation
        validateProductIntegrity(product, clientId, orgId);

        // Duplicate Code Check
        if (product.getProductCode() != null && productRepository
                .existsByProductCodeAndClientIdAndOrgIdOrGlobal(product.getProductCode(), clientId, orgId)) {
            throw new BusinessException("Product with this code already exists");
        }

        product.setClientId(clientId);
        product.setOrgId(orgId);

        setProductRelationships(product, clientId, orgId);

        return productRepository.save(product);
    }

    private void setProductRelationships(Product product, UUID clientId, UUID orgId) {
        if (product.getVariantMappings() != null) {
            product.getVariantMappings().forEach(vm -> {
                if (vm.getVariantGroup() != null && vm.getVariantGroup().getId() != null) {
                    VariantGroup group = variantGroupRepository.findById(vm.getVariantGroup().getId())
                            .orElseThrow(() -> new ResourceNotFoundException("Variant Group not found"));
                    validateOwnership(group.getClientId(), group.getOrgId(), "Variant Group", false);
                    vm.setVariantGroup(group);
                }
                vm.setProduct(product);
                vm.setClientId(clientId);
                vm.setOrgId(orgId);
            });
        }
        if (product.getVariantPricings() != null) {
            product.getVariantPricings().forEach(vp -> {
                if (vp.getVariantOption() != null && vp.getVariantOption().getId() != null) {
                    VariantOption option = variantOptionRepository.findById(vp.getVariantOption().getId())
                            .orElseThrow(() -> new ResourceNotFoundException("Variant Option not found"));
                    validateOwnership(option.getClientId(), option.getOrgId(), "Variant Option", false);
                    vp.setVariantOption(option);
                }
                vp.setProduct(product);
                vp.setClientId(clientId);
                vp.setOrgId(orgId);
            });
        }
        if (product.getUpsells() != null) {
            product.getUpsells().forEach(upsell -> {
                // Circular Check
                if (product.getId() != null && upsell.getUpsellProduct() != null
                        && product.getId().equals(upsell.getUpsellProduct().getId())) {
                    throw new BusinessException("A product cannot be an upsell to itself");
                }
                upsell.setParentProduct(product);
                upsell.setClientId(clientId);
                upsell.setOrgId(orgId);
            });
        }
        if (product.getPricelistProducts() != null) {
            product.getPricelistProducts().forEach(pp -> {
                pp.setProduct(product);
                pp.setClientId(clientId);
                pp.setOrgId(orgId);
            });
        }
        if (product.getRecipeLines() != null) {
            product.getRecipeLines().forEach(recipe -> {
                if (product.getId() != null && recipe.getIngredient() != null
                        && product.getId().equals(recipe.getIngredient().getId())) {
                    throw new BusinessException("A product cannot be an ingredient of itself");
                }
                recipe.setProduct(product);
                recipe.setClientId(clientId);
                recipe.setOrgId(orgId);
            });
        }
    }

    @Transactional
    @CacheEvict(value = "products_list_v3", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public List<Product> bulkCreateProducts(List<Product> products) {
        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = TenantContext.getCurrentOrg();

        java.util.Set<String> batchCodes = new java.util.HashSet<>();

        // Optimization: Pre-fetch everything for validation
        java.util.Set<UUID> validCategoryIds = categoryRepository.findByClientIdAndOrgIdOrGlobal(clientId, orgId)
                .stream().map(Category::getId).collect(Collectors.toSet());
        java.util.Set<UUID> validUomIds = uomRepository.findByClientIdAndOrgIdOrGlobal(clientId, orgId)
                .stream().map(Uom::getId).collect(Collectors.toSet());
        Map<String, Category> categoryNameMap = categoryRepository.findByClientIdAndOrgIdOrGlobal(clientId, orgId)
                .stream().collect(Collectors.toMap(Category::getName, c -> c, (a, b) -> a));

        for (Product product : products) {
            // Batch Duplicate Check
            if (product.getProductCode() != null) {
                if (batchCodes.contains(product.getProductCode())) {
                    throw new BusinessException("Duplicate product code in batch: " + product.getProductCode());
                }
                if (productRepository.existsByProductCodeAndClientIdAndOrgIdOrGlobal(product.getProductCode(), clientId,
                        orgId)) {
                    throw new BusinessException("Product code already exists in DB: " + product.getProductCode());
                }
                batchCodes.add(product.getProductCode());
            }

            product.setClientId(clientId);
            product.setOrgId(orgId);

            // Perform integrity check against pre-fetched sets for O(1) speed
            validateProductIntegrityOptimized(product, clientId, orgId, validCategoryIds, validUomIds);

            setProductRelationships(product, clientId, orgId);

            // Resolve category efficiently
            if (product.getCategory() != null && product.getCategory().getId() == null
                    && product.getCategory().getName() != null) {
                String catName = product.getCategory().getName();
                Category category = categoryNameMap.get(catName);
                if (category == null) {
                    category = new Category();
                    category.setName(catName);
                    category.setClientId(clientId);
                    category.setOrgId(orgId);
                    category = categoryRepository.save(category);
                    categoryNameMap.put(catName, category);
                    validCategoryIds.add(category.getId());
                }
                product.setCategory(category);
            }
        }
        @SuppressWarnings("null")
        List<Product> savedProducts = productRepository.saveAll(products);
        return savedProducts;
    }

    @Transactional
    @CacheEvict(value = "products_list_v4", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public Product updateProduct(UUID id, Product product) {
        Product existing = productRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        validateOwnership(existing.getClientId(), existing.getOrgId(), "Product");

        UUID clientId = TenantContext.getCurrentTenant();
        UUID orgId = effectiveWriteOrgId(existing.getOrgId());
        validateProductIntegrity(product, clientId, orgId);

        existing.setName(product.getName());
        existing.setDescription(product.getDescription());
        existing.setPrice(product.getPrice());
        existing.setAvailable(product.isAvailable());
        existing.setImageUrl(product.getImageUrl());
        existing.setActive(product.isActive());

        // ERP Fields
        existing.setProductType(product.getProductType());
        existing.setVariant(product.isVariant());
        existing.setPackagedGood(product.isPackagedGood());
        existing.setIngredient(product.isIngredient());

        String newCode = product.getProductCode() != null && product.getProductCode().trim().isEmpty() ? null : product.getProductCode();
        if (newCode != null && !newCode.equals(existing.getProductCode()) &&
                productRepository.existsByProductCodeAndClientIdAndOrgIdOrGlobal(newCode, clientId, orgId)) {
            throw new BusinessException("Product with this code already exists");
        }
        existing.setProductCode(newCode);

        existing.setTaxRate(product.getTaxRate());
        existing.setTaxCode(product.getTaxCode());
        existing.setMrp(product.getMrp());
        existing.setCostPrice(product.getCostPrice());
        existing.setBarcode(product.getBarcode());
        existing.setMinStockLevel(product.getMinStockLevel());
        existing.setKdsStation(product.getKdsStation());

        existing.setCategory(resolveCategoryReference(product.getCategory(), clientId, orgId));
        existing.setUom(resolveUomReference(product.getUom(), clientId, orgId));
        existing.setDefaultPricelist(product.getDefaultPricelist());

        setProductRelationships(product, clientId, orgId);

        // Update Mappings
        existing.getVariantMappings().clear();
        if (product.getVariantMappings() != null) {
            product.getVariantMappings().forEach(vm -> {
                vm.setVariantGroup(resolveVariantGroupReference(vm.getVariantGroup()));
                vm.setProduct(existing);
                vm.setClientId(clientId);
                vm.setOrgId(orgId);
            });
            existing.getVariantMappings().addAll(product.getVariantMappings());
        }

        // Update Pricings
        existing.getVariantPricings().clear();
        if (product.getVariantPricings() != null) {
            product.getVariantPricings().forEach(vp -> {
                vp.setVariantOption(resolveVariantOptionReference(vp.getVariantOption()));
                vp.setProduct(existing);
                vp.setClientId(clientId);
                vp.setOrgId(orgId);
            });
            existing.getVariantPricings().addAll(product.getVariantPricings());
        }

        // Update Upsells
        existing.getUpsells().clear();
        if (product.getUpsells() != null) {
            product.getUpsells().forEach(upsell -> {
                if (existing.getId() != null && upsell.getUpsellProduct() != null
                        && existing.getId().equals(upsell.getUpsellProduct().getId())) {
                    throw new BusinessException("A product cannot be an upsell to itself");
                }
                if (upsell.getUpsellProduct() != null && upsell.getUpsellProduct().getId() != null) {
                    Product upsellProduct = productRepository.findById(upsell.getUpsellProduct().getId())
                            .orElseThrow(() -> new ResourceNotFoundException("Upsell product not found"));
                    validateOwnership(upsellProduct.getClientId(), upsellProduct.getOrgId(), "Upsell Product", false);
                    upsell.setUpsellProduct(upsellProduct);
                }
                upsell.setParentProduct(existing);
                upsell.setClientId(clientId);
                upsell.setOrgId(orgId);
            });
            existing.getUpsells().addAll(product.getUpsells());
        }

        // Update Pricelist Products
        existing.getPricelistProducts().clear();
        if (product.getPricelistProducts() != null) {
            existing.getPricelistProducts().addAll(product.getPricelistProducts());
        }

        // Update Recipe Lines
        existing.getRecipeLines().clear();
        if (product.getRecipeLines() != null) {
            product.getRecipeLines().forEach(recipe -> {
                if (existing.getId() != null && recipe.getIngredient() != null
                        && existing.getId().equals(recipe.getIngredient().getId())) {
                    throw new BusinessException("A product cannot be an ingredient of itself");
                }
                if (recipe.getIngredient() != null && recipe.getIngredient().getId() != null) {
                    Product ingredientProduct = productRepository.findById(recipe.getIngredient().getId())
                            .orElseThrow(() -> new ResourceNotFoundException("Ingredient product not found"));
                    validateOwnership(ingredientProduct.getClientId(), ingredientProduct.getOrgId(), "Ingredient Product", false);
                    if (!ingredientProduct.isIngredient()) {
                        throw new BusinessException("Product must be set as an ingredient to be used in a recipe");
                    }
                    recipe.setIngredient(ingredientProduct);
                }
                recipe.setProduct(existing);
                recipe.setClientId(clientId);
                recipe.setOrgId(orgId);
            });
            existing.getRecipeLines().addAll(product.getRecipeLines());
        }

        return productRepository.save(existing);
    }

    private Category resolveCategoryReference(Category category, UUID clientId, UUID orgId) {
        if (category == null || category.getId() == null) {
            return null;
        }

        Category resolved = categoryRepository.findById(category.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        validateOwnership(resolved.getClientId(), resolved.getOrgId(), "Category", false);
        return resolved;
    }

    private Uom resolveUomReference(Uom uom, UUID clientId, UUID orgId) {
        if (uom == null || uom.getId() == null) {
            return null;
        }

        Uom resolved = uomRepository.findById(uom.getId())
                .orElseThrow(() -> new ResourceNotFoundException("UOM not found"));
        validateOwnership(resolved.getClientId(), resolved.getOrgId(), "UOM", false);
        return resolved;
    }

    private VariantGroup resolveVariantGroupReference(VariantGroup group) {
        if (group == null || group.getId() == null) {
            return null;
        }

        VariantGroup resolved = variantGroupRepository.findById(group.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Variant Group not found"));
        validateOwnership(resolved.getClientId(), resolved.getOrgId(), "Variant Group", false);
        return resolved;
    }

    private VariantOption resolveVariantOptionReference(VariantOption option) {
        if (option == null || option.getId() == null) {
            return null;
        }

        VariantOption resolved = variantOptionRepository.findById(option.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Variant Option not found"));
        validateOwnership(resolved.getClientId(), resolved.getOrgId(), "Variant Option", false);
        return resolved;
    }

    @Transactional
    @CacheEvict(value = "products_list_v2", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public Product updateProductStatus(UUID id, boolean active) {
        Product existing = productRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        validateOwnership(existing.getClientId(), existing.getOrgId(), "Product");

        existing.setActive(active);
        existing.setAvailable(active);
        return productRepository.save(existing);
    }

    @Transactional
    @CacheEvict(value = "products_list_v3", key = "T(com.restaurant.pos.common.tenant.TenantContext).getCurrentTenant() + ':' + T(com.restaurant.pos.common.tenant.TenantContext).getCurrentOrg()")
    public void deleteProduct(UUID id) {
        Product existing = productRepository.findById(java.util.Objects.requireNonNull(id))
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));

        validateOwnership(existing.getClientId(), existing.getOrgId(), "Product");

        // Soft delete
        existing.setActive(false);
        productRepository.save(existing);
    }

    private void validateOwnership(UUID ownerClientId, UUID ownerOrgId, String entityName) {
        validateOwnership(ownerClientId, ownerOrgId, entityName, true);
    }

    private void validateOwnership(UUID ownerClientId, UUID ownerOrgId, String entityName, boolean forModification) {
        UUID currentClientId = TenantContext.getCurrentTenant();
        UUID currentOrgId = TenantContext.getCurrentOrg();

        // 1. Cross-Tenant Check
        if (!currentClientId.equals(ownerClientId)) {
            throw new BusinessException("Access denied: " + entityName + " belongs to another tenant");
        }

        if (SecurityUtils.isSuperAdmin() && currentOrgId == null) {
            return;
        }

        // 2. Global Data Protection (Global records have NULL orgId)
        if (forModification && ownerOrgId == null && currentOrgId != null) {
            // Check if user has permission to modify global data (assuming only
            // SuperAdmin/System, for now rejecting all non-system)
            // In a real scenario, we'd check Roles. Here we protect global data from being
            // deleted/updated by org users.
            throw new BusinessException("Access denied: Global " + entityName + " cannot be modified by branch users");
        }

        // 3. Cross-Org Check
        if (ownerOrgId != null && !java.util.Objects.equals(currentOrgId, ownerOrgId)) {
            throw new BusinessException("Access denied: " + entityName + " belongs to another organization");
        }
    }

    private UUID effectiveWriteOrgId(UUID ownerOrgId) {
        UUID currentOrgId = TenantContext.getCurrentOrg();
        return currentOrgId != null ? currentOrgId : ownerOrgId;
    }

    private void validateProductIntegrity(Product product, UUID clientId, UUID orgId) {
        // Deep Validation for individual create/update (uses standard lookup)
        if (product.getCategory() != null && product.getCategory().getId() != null) {
            Category cat = categoryRepository.findById(java.util.Objects.requireNonNull(product.getCategory().getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
            validateOwnership(cat.getClientId(), cat.getOrgId(), "Category", false);
        }

        if (product.getUom() != null && product.getUom().getId() != null) {
            Uom uom = uomRepository.findById(java.util.Objects.requireNonNull(product.getUom().getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("UOM not found"));
            validateOwnership(uom.getClientId(), uom.getOrgId(), "UOM", false);
        }

        // Upsell Check
        if (product.getUpsells() != null) {
            for (var upsell : product.getUpsells()) {
                if (upsell.getUpsellProduct() != null && upsell.getUpsellProduct().getId() != null) {
                    Product upProduct = productRepository.findById(java.util.Objects.requireNonNull(upsell.getUpsellProduct().getId()))
                            .orElseThrow(() -> new ResourceNotFoundException("Upsell product not found"));
                    validateOwnership(upProduct.getClientId(), upProduct.getOrgId(), "Upsell Product", false);
                }
            }
        }
    }

    private void validateProductIntegrityOptimized(Product product, UUID clientId, UUID orgId,
            java.util.Set<UUID> validCats, java.util.Set<UUID> validUoms) {
        if (product.getCategory() != null && product.getCategory().getId() != null) {
            if (!validCats.contains(product.getCategory().getId())) {
                throw new BusinessException("Invalid category selected or access denied");
            }
        }
        if (product.getUom() != null && product.getUom().getId() != null) {
            if (!validUoms.contains(product.getUom().getId())) {
                throw new BusinessException("Invalid UOM selected or access denied");
            }
        }
    }
}
