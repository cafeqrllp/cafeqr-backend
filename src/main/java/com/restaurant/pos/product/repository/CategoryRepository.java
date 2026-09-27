package com.restaurant.pos.product.repository;

import com.restaurant.pos.product.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {
    UUID NIL_ORG_ID = new UUID(0L, 0L);

    @Query("SELECT c FROM Category c WHERE (c.clientId = :clientId OR c.clientId IS NULL) AND (:orgId IS NULL OR c.orgId = :orgId OR c.orgId IS NULL OR c.orgId = :nilOrgId) ORDER BY LOWER(c.name) ASC")
    List<Category> findByClientIdAndOrgIdOrGlobalInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default List<Category> findByClientIdAndOrgIdOrGlobal(UUID clientId, UUID orgId) {
        return findByClientIdAndOrgIdOrGlobalInternal(clientId, orgId, NIL_ORG_ID);
    }

    @Query("SELECT c FROM Category c WHERE (c.clientId = :clientId OR c.clientId IS NULL) AND (:orgId IS NULL OR c.orgId = :orgId OR c.orgId IS NULL OR c.orgId = :nilOrgId) AND c.isActive = true ORDER BY LOWER(c.name) ASC")
    List<Category> findByClientIdAndOrgIdOrGlobalAndIsActiveTrueInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default List<Category> findByClientIdAndOrgIdOrGlobalAndIsActiveTrue(UUID clientId, UUID orgId) {
        return findByClientIdAndOrgIdOrGlobalAndIsActiveTrueInternal(clientId, orgId, NIL_ORG_ID);
    }

    @Query("SELECT c FROM Category c WHERE (c.clientId = :clientId OR c.clientId IS NULL) AND (:orgId IS NULL OR c.orgId = :orgId OR c.orgId IS NULL OR c.orgId = :nilOrgId) AND c.updatedAt >= :updatedAfter ORDER BY LOWER(c.name) ASC")
    List<Category> findChangedByClientIdAndOrgIdOrGlobalInternal(@Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId, @Param("updatedAfter") LocalDateTime updatedAfter);

    default List<Category> findChangedByClientIdAndOrgIdOrGlobal(UUID clientId, UUID orgId, LocalDateTime updatedAfter) {
        return findChangedByClientIdAndOrgIdOrGlobalInternal(clientId, orgId, NIL_ORG_ID, updatedAfter);
    }
    
    @Query("SELECT c FROM Category c WHERE c.id = :id AND (c.clientId = :clientId OR c.clientId IS NULL) AND (:orgId IS NULL OR c.orgId = :orgId OR c.orgId IS NULL OR c.orgId = :nilOrgId)")
    Optional<Category> findByIdAndClientIdAndOrgIdOrGlobalInternal(@Param("id") UUID id, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default Optional<Category> findByIdAndClientIdAndOrgIdOrGlobal(UUID id, UUID clientId, UUID orgId) {
        return findByIdAndClientIdAndOrgIdOrGlobalInternal(id, clientId, orgId, NIL_ORG_ID);
    }

    @Query("SELECT c FROM Category c WHERE LOWER(c.name) = LOWER(:name) AND (c.clientId = :clientId OR c.clientId IS NULL) AND (:orgId IS NULL OR c.orgId = :orgId OR c.orgId IS NULL OR c.orgId = :nilOrgId)")
    Optional<Category> findByNameAndClientIdAndOrgIdOrGlobalInternal(@Param("name") String name, @Param("clientId") UUID clientId, @Param("orgId") UUID orgId, @Param("nilOrgId") UUID nilOrgId);

    default Optional<Category> findByNameAndClientIdAndOrgIdOrGlobal(String name, UUID clientId, UUID orgId) {
        return findByNameAndClientIdAndOrgIdOrGlobalInternal(name, clientId, orgId, NIL_ORG_ID);
    }
}
