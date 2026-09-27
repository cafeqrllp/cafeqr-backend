package com.restaurant.pos.product.repository;

import com.restaurant.pos.product.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {
    UUID NIL_ORG_ID = new UUID(0L, 0L);

    List<Product> findByClientId(UUID clientId);

    @EntityGraph(attributePaths = {"category"})
    Optional<Product> findWithCategoryById(UUID id);

    @EntityGraph(attributePaths = {"category", "uom", "defaultPricelist"})
    @Query("SELECT p FROM Product p WHERE (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId)")
    List<Product> findByClientIdAndOrgIdOrGlobalInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default List<Product> findByClientIdAndOrgIdOrGlobal(UUID clientId, UUID orgId) {
        return findByClientIdAndOrgIdOrGlobalInternal(clientId, orgId, NIL_ORG_ID);
    }

    @EntityGraph(attributePaths = {"category", "uom", "defaultPricelist"})
    @Query(value = "SELECT p FROM Product p WHERE (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId) " +
           "AND (:categoryId IS NULL OR p.category.id = :categoryId) " +
           "AND (:isActive IS NULL OR p.isActive = :isActive)",
           countQuery = "SELECT count(p) FROM Product p WHERE (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId) " +
           "AND (:categoryId IS NULL OR p.category.id = :categoryId) " +
           "AND (:isActive IS NULL OR p.isActive = :isActive)")
    Page<Product> findFilteredInternal(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("nilOrgId") UUID nilOrgId,
            @Param("categoryId") UUID categoryId,
            @Param("isActive") Boolean isActive,
            Pageable pageable);

    default Page<Product> findFiltered(UUID clientId, UUID orgId, UUID categoryId, Boolean isActive, Pageable pageable) {
        return findFilteredInternal(clientId, orgId, NIL_ORG_ID, categoryId, isActive, pageable);
    }

    @EntityGraph(attributePaths = {"category", "uom", "defaultPricelist"})
    @Query(value = "SELECT p FROM Product p WHERE (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId) " +
           "AND (:categoryId IS NULL OR p.category.id = :categoryId) " +
           "AND (:isActive IS NULL OR p.isActive = :isActive) " +
           "AND (LOWER(p.name) LIKE :pattern OR (p.productCode IS NOT NULL AND LOWER(p.productCode) LIKE :pattern))",
           countQuery = "SELECT count(p) FROM Product p WHERE (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId) " +
           "AND (:categoryId IS NULL OR p.category.id = :categoryId) " +
           "AND (:isActive IS NULL OR p.isActive = :isActive) " +
           "AND (LOWER(p.name) LIKE :pattern OR (p.productCode IS NOT NULL AND LOWER(p.productCode) LIKE :pattern))")
    Page<Product> findFilteredWithSearchInternal(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("nilOrgId") UUID nilOrgId,
            @Param("categoryId") UUID categoryId,
            @Param("isActive") Boolean isActive,
            @Param("pattern") String pattern,
            Pageable pageable);

    default Page<Product> findFilteredWithSearch(UUID clientId, UUID orgId, UUID categoryId, Boolean isActive, String pattern, Pageable pageable) {
        return findFilteredWithSearchInternal(clientId, orgId, NIL_ORG_ID, categoryId, isActive, pattern, pageable);
    }

    @EntityGraph(attributePaths = {"category", "uom", "defaultPricelist"})
    @Query("SELECT p FROM Product p WHERE (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId) AND p.isActive = true")
    List<Product> findByClientIdAndOrgIdOrGlobalAndIsActiveTrueInternal(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("nilOrgId") UUID nilOrgId);

    default List<Product> findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(UUID clientId, UUID orgId) {
        return findByClientIdAndOrgIdOrGlobalAndIsActiveTrueInternal(clientId, orgId, NIL_ORG_ID);
    }

    @EntityGraph(attributePaths = {"category", "uom"})
    @Query("SELECT p FROM Product p WHERE (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId) AND p.updatedAt >= :updatedAfter")
    List<Product> findChangedByClientIdAndOrgIdOrGlobalInternal(
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("nilOrgId") UUID nilOrgId,
            @Param("updatedAfter") LocalDateTime updatedAfter);

    default List<Product> findChangedByClientIdAndOrgIdOrGlobal(UUID clientId, UUID orgId, LocalDateTime updatedAfter) {
        return findChangedByClientIdAndOrgIdOrGlobalInternal(clientId, orgId, NIL_ORG_ID, updatedAfter);
    }

    @EntityGraph(attributePaths = {"category", "uom"})
    List<Product> findByIdIn(List<UUID> ids);

    @Query("SELECT COUNT(p) > 0 FROM Product p WHERE p.productCode = :code AND (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId) AND p.isActive = true")
    boolean existsByProductCodeAndClientIdAndOrgIdOrGlobalInternal(
            @Param("code") String code,
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("nilOrgId") UUID nilOrgId);

    default boolean existsByProductCodeAndClientIdAndOrgIdOrGlobal(String code, UUID clientId, UUID orgId) {
        return existsByProductCodeAndClientIdAndOrgIdOrGlobalInternal(code, clientId, orgId, NIL_ORG_ID);
    }

    @Query("SELECT COUNT(p) > 0 FROM Product p WHERE LOWER(p.name) = LOWER(:name) AND (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId) AND p.isActive = true AND (:id IS NULL OR p.id != :id)")
    boolean existsByNameAndClientIdAndOrgIdOrGlobalAndIdNotInternal(
            @Param("name") String name,
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("nilOrgId") UUID nilOrgId,
            @Param("id") UUID id);

    default boolean existsByNameAndClientIdAndOrgIdOrGlobalAndIdNot(String name, UUID clientId, UUID orgId, UUID id) {
        return existsByNameAndClientIdAndOrgIdOrGlobalAndIdNotInternal(name, clientId, orgId, NIL_ORG_ID, id);
    }

    @Query("SELECT COUNT(p) > 0 FROM Product p WHERE LOWER(p.productCode) = LOWER(:code) AND (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId) AND p.isActive = true AND (:id IS NULL OR p.id != :id)")
    boolean existsByProductCodeAndClientIdAndOrgIdOrGlobalAndIdNotInternal(
            @Param("code") String code,
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("nilOrgId") UUID nilOrgId,
            @Param("id") UUID id);

    default boolean existsByProductCodeAndClientIdAndOrgIdOrGlobalAndIdNot(String code, UUID clientId, UUID orgId, UUID id) {
        return existsByProductCodeAndClientIdAndOrgIdOrGlobalAndIdNotInternal(code, clientId, orgId, NIL_ORG_ID, id);
    }

    boolean existsByVariantMappings_VariantGroup_IdAndIsActiveTrue(UUID variantGroupId);

    boolean existsByVariantPricings_VariantOption_IdAndIsActiveTrue(UUID variantOptionId);

    @EntityGraph(attributePaths = {"category", "uom", "defaultPricelist"})
    @Query("SELECT p FROM Product p WHERE p.barcode = :barcode AND (p.clientId = :clientId OR p.clientId IS NULL) AND (:orgId IS NULL OR p.orgId = :orgId OR p.orgId IS NULL OR p.orgId = :nilOrgId) AND p.isActive = true")
    Optional<Product> findByBarcodeAndClientIdAndOrgIdOrGlobalInternal(
            @Param("barcode") String barcode,
            @Param("clientId") UUID clientId,
            @Param("orgId") UUID orgId,
            @Param("nilOrgId") UUID nilOrgId);

    default Optional<Product> findByBarcodeAndClientIdAndOrgIdOrGlobal(String barcode, UUID clientId, UUID orgId) {
        return findByBarcodeAndClientIdAndOrgIdOrGlobalInternal(barcode, clientId, orgId, NIL_ORG_ID);
    }
}
